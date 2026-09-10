package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraConnection
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import android.os.Handler
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*

/** Native tests cover physical work. Route selection and retained configuration are tested in Dart. */
internal class ScannerCaptureTest {
    @Test
    fun `capture applies all settings before binding focus and starting analysis`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            clearInvocations(f.camera, f.first)
            val zoom = CompletableDeferred<Unit>()
            val torch = CompletableDeferred<Unit>()
            f.zoom += zoom
            doReturn(torch).`when`(f.camera).setTorch(true)
            val configure = f.calls.async {
                f.scanner.select(f.first)
                f.scanner.capture(ScannerConfiguration(zoomRatio = 3F, torchEnabled = true, scanEnabled = true, scanDelay = 250)) { true }
            }
            verify(f.first).setScanActive(false)
            verify(f.first, never()).bindFocus()
            verify(f.first, never()).setScanActive(true)
            zoom.complete(Unit)
            verify(f.camera).setTorch(true)
            verify(f.first, never()).bindFocus()
            torch.complete(Unit)
            configure.await()
            inOrder(f.camera, f.first).apply {
                verify(f.camera).setZoomRatio(3F)
                verify(f.camera).setTorch(true)
                verify(f.first).bindFocus()
                verify(f.first).setScanActive(true)
            }
        } finally { f.close() }
    }

    @Test
    fun `point controls keep preview focus and analysis running`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            clearInvocations(f.camera, f.first)
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val change = f.calls.async { f.scanner.setZoomRatio(3F) }
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
            verify(f.first, never()).setScanActive(false)
            zoom.complete(Unit)
            change.await()
            f.scanner.setTorch(true)
            f.scanner.setScanPeriod(250)
            f.scanner.setCropArea(com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect(scaleWidth = 0.5))
            verify(f.camera, never()).resetFocus()
            verify(f.first, never()).bindFocus()
            verify(f.first, never()).setScanActive(false)
        } finally { f.close() }
    }

    @Test
    fun `permission completes before any SDK binding`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val permission = CompletableDeferred<Boolean>()
            val capture = f.capture { permission.await() }
            verify(f.camera, never()).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
            permission.complete(true)
            f.open()
            capture.await()
            verify(f.first).bindFocus()
        } finally { f.close() }
    }

    @Test
    fun `denied permission does not bind hardware`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val error = runCatching { f.capture { false }.await() }.exceptionOrNull()
            assertSame(PluginError.AuthorizationCameraError, error)
            verify(f.camera, never()).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
        } finally { f.close() }
    }

    @Test
    fun `release cancels permission waiter without cancelling shared permission dialog`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val permission = CompletableDeferred<Boolean>()
            val capture = f.capture { permission.await() }
            f.scanner.releaseCamera()
            capture.await()
            assertFalse(permission.isCancelled)
            permission.complete(true)
            verify(f.camera, never()).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
        } finally { f.close() }
    }

    @Test
    fun `binding and OPEN may arrive in either order`() = runBlocking<Unit> {
        for (openFirst in listOf(true, false)) {
            val f = Fixture()
            try {
                val capture = f.capture()
                if (openFirst) f.availability(CameraAvailability.Open) else f.initialized()
                assertFalse(capture.isCompleted)
                if (openFirst) f.initialized() else f.availability(CameraAvailability.Open)
                capture.await()
                verify(f.camera).setZoomRatio(1F)
                verify(f.camera).setTorch(false)
            } finally { f.close() }
        }
    }

    @Test
    fun `capture borrows Activity lifecycle and uses SDK readiness in STARTED state`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            (f.host.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED
            val capture = f.capture()
            assertSame(f.host.lifecycle, f.boundOwners.single().lifecycle)
            assertFalse(capture.isCompleted)
            f.open()
            capture.await()
            verify(f.first).bindFocus()
            assertEquals(Lifecycle.State.STARTED, f.host.lifecycle.currentState)
        } finally { f.close() }
    }

    @Test
    fun `synchronous bind failure preserves primary error through failing cleanup`() = runBlocking<Unit> {
        val f = Fixture()
        val failure = IllegalStateException("binding")
        val cleanup = IllegalStateException("dispose")
        doThrow(failure).`when`(f.camera).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
        doThrow(cleanup).`when`(f.camera).dispose()
        val result = f.capture()
        val error = runCatching { result.await() }.exceptionOrNull()
        assertEquals(failure.message, error?.message)
        assertTrue(failure.suppressed.contains(cleanup))
        assertTrue(f.scanner.isDisposed)
        f.close()
    }

    @Test
    fun `recovered OPEN permits a new capture after a temporary camera error`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val first = f.capture()
            f.initialized()
            f.availability(CameraAvailability.Closed(errorCode = 3))
            assertTrue(runCatching { first.await() }.exceptionOrNull() is PluginError.CameraControlError)
            f.availability(CameraAvailability.Open)
            f.capture().await()
            verify(f.first).bindFocus()
        } finally { f.close() }
    }

    @Test
    fun `OPEN failure before binding acknowledgement completes capture`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val capture = f.capture()
            f.availability(CameraAvailability.Closed(errorCode = 3))
            assertTrue(capture.isCompleted)
            val failure = runCatching { capture.await() }.exceptionOrNull()
            assertTrue(failure is PluginError.CameraControlError)
            f.initialized()
            f.availability(CameraAvailability.Open)
            verify(f.first, never()).bindFocus()
            f.capture().await()
            verify(f.first).bindFocus()
        } finally { f.close() }
    }

    @Test
    fun `capture waits for controls before acknowledging readiness`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()
            assertFalse(capture.isCompleted)
            verify(f.first, never()).bindFocus()
            verify(f.camera, never()).setTorch(false)
            zoom.complete(Unit)
            capture.await()
            verify(f.first).bindFocus()
        } finally { f.close() }
    }

    @Test
    fun `release cancels actual SDK future and prevents remaining capture controls`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()
            f.scanner.releaseCamera()
            capture.await()
            assertTrue(zoom.isCancelled)
            verify(f.camera, never()).setTorch(false)
            verify(f.first, never()).bindFocus()
        } finally { f.close() }
    }

    @Test
    fun `Activity detach cancels SDK control and unbinds immediately while retaining adapters`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()

            f.scanner.detachActivity()
            capture.await()

            assertTrue(zoom.isCancelled)
            assertNull(f.scanner.viewId)
            assertFalse(f.scanner.isDisposed)
            verify(f.camera).unbind()
            verify(f.camera, never()).setTorch(false)
            verify(f.first, never()).bindFocus()
            verify(f.camera, never()).dispose()
            verify(f.analyzer, never()).dispose()
            assertEquals(listOf(300L), f.delays)
            assertEquals(Lifecycle.State.RESUMED, f.host.lifecycle.currentState)
        } finally { f.close() }
    }

    @Test
    fun `next Dart capture rebinds replacement Activity and ignores old binding callbacks`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val oldCapture = f.capture()
            val oldInit = f.initialized
            val oldAvailability = f.availability
            val oldFailure = f.failed
            f.scanner.detachActivity()
            oldCapture.await()
            val replacement = mock(Lifecycle::class.java)

            f.scanner.attachActivity(replacement)
            assertEquals(1, f.boundOwners.size)
            f.scanner.select(f.second)
            val capture = f.calls.async {
                f.scanner.capture(ScannerConfiguration(zoomRatio = 4F, torchEnabled = true)) { true }
            }
            assertEquals(2, f.boundOwners.size)
            assertSame(replacement, f.boundOwners.last().lifecycle)

            oldInit()
            oldAvailability(CameraAvailability.Open)
            oldFailure(IllegalStateException("old Activity"))
            assertFalse(capture.isCompleted)
            assertFalse(f.scanner.isDisposed)
            f.initialized()
            assertFalse(capture.isCompleted)
            f.availability(CameraAvailability.Open)
            capture.await()

            verify(f.camera).setZoomRatio(4F)
            verify(f.camera).setTorch(true)
            verify(f.second).bindFocus()
            assertEquals(43, f.scanner.viewId)
            verify(f.camera, never()).dispose()
        } finally { f.close() }
    }

    @Test
    fun `Activity detach forgets readiness of previously open camera`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.detachActivity()
            f.scanner.attachActivity(mock(Lifecycle::class.java))
            val capture = f.capture()
            assertEquals(2, f.boundOwners.size)
            f.initialized()
            assertFalse(capture.isCompleted)
            f.availability(CameraAvailability.Open)
            capture.await()
            verify(f.first, times(2)).bindFocus()
        } finally { f.close() }
    }

    @Test
    fun `caller cancellation also cancels its hardware future`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()
            capture.cancelAndJoin()
            assertTrue(zoom.isCancelled)
        } finally { f.close() }
    }

    @Test
    fun `release keeps SDK resources until native grace expires`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.releaseCamera()
            assertEquals(listOf(300L), f.delays)
            assertEquals(Lifecycle.State.RESUMED, f.host.lifecycle.currentState)
            assertFalse(f.scanner.isDisposed)
            f.timers.single().run()
            assertTrue(f.scanner.isDisposed)
            assertEquals(Lifecycle.State.RESUMED, f.host.lifecycle.currentState)
            verify(f.camera).dispose()
            verify(f.first, never()).release()
        } finally { f.close() }
    }

    @Test
    fun `new consumer reuses SDK and cancels pending idle disposal`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.releaseCamera()
            f.scanner.select(f.second)
            assertFalse(f.scanner.isDisposed)
            assertEquals(43, f.scanner.viewId)
            assertTrue(f.timers.isEmpty())
        } finally { f.close() }
    }

    @Test
    fun `full snapshot is applied afresh on every capture`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.select(f.second)
            f.scanner.capture(ScannerConfiguration(zoomRatio = 4F, torchEnabled = true,
                scanEnabled = true, scanDelay = 120)) { true }
            verify(f.camera).setZoomRatio(4F)
            verify(f.camera).setTorch(true)
            f.emitFrame()
            verify(f.analyzer).analyze(anyValue(), anyValue())
            verify(f.second).setScanActive(true)
        } finally { f.close() }
    }

    @Test
    fun `queued barcode is dropped after release and recapture of same view`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            assertTrue(f.posts.isNotEmpty())
            f.scanner.releaseCamera()
            f.scanner.select(f.first)
            f.scanner.capture(ScannerConfiguration(scanEnabled = true)) { true }
            f.drain()
            assertTrue(f.results.isEmpty())
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
        } finally { f.close() }
    }

    @Test
    fun `cancel and restart replace delivery subscription without changing preview`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            f.scanner.pauseScan()
            f.scanner.startScan(0)
            f.drain()
            assertTrue(f.results.isEmpty())
            assertEquals(42, f.scanner.viewId)
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
        } finally { f.close() }
    }

    @Test
    fun `SDK CLOSED suppresses queued worker results`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            f.availability(CameraAvailability.Closed())
            f.drain()
            assertTrue(f.results.isEmpty())
        } finally { f.close() }
    }

    @Test
    fun `preview readiness gates analysis`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            doReturn(false).`when`(f.first).isPreviewReady()
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            verify(f.analyzer, never()).analyze(anyValue(), anyValue())
            doReturn(true).`when`(f.first).isPreviewReady()
            f.previewReady()
            f.emitFrame()
            verify(f.analyzer).analyze(anyValue(), anyValue())
        } finally { f.close() }
    }

    @Test
    fun `closing stops analysis and reopening cannot revive queued results`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            f.availability(CameraAvailability.Closed())
            f.emitFrame()
            verify(f.analyzer).analyze(anyValue(), anyValue())
            f.availability(CameraAvailability.Open)
            f.drain()
            assertTrue(f.results.isEmpty())
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
        } finally { f.close() }
    }

    @Test
    fun `startup failure releases hardware but not borrowed view`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val capture = f.capture()
            val failure = IllegalStateException("bind")
            f.failed(failure)
            // Await may copy ordinary exceptions to recover coroutine stack frames.
            val reported = runCatching { capture.await() }.exceptionOrNull()
            assertEquals(failure.javaClass, reported?.javaClass)
            assertEquals(failure.message, reported?.message)
            assertTrue(f.scanner.isDisposed)
            verify(f.camera).dispose()
            verify(f.first, never()).release()
            assertEquals(listOf(failure), f.released)
        } finally { f.close() }
    }

    @Test
    fun `cleanup failure does not skip terminal notification or view detachment`() = runBlocking<Unit> {
        val f = Fixture()
        f.activate()
        val failure = IllegalStateException("dispose")
        doThrow(failure).`when`(f.camera).dispose()
        assertSame(failure, runCatching { f.scanner.dispose() }.exceptionOrNull())
        assertTrue(f.scanner.isDisposed)
        assertFalse(f.scanner.scope.isActive)
        assertNull(f.scanner.viewId)
        assertEquals(1, f.released.size)
        f.scanner.dispose()
        verify(f.camera).dispose()
        f.close()
    }

    @Test
    fun `control failure carries operation and current view address`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.zoom += CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("zoom")) }
            val error = runCatching { f.scanner.setZoomRatio(2F) }.exceptionOrNull()
            assertTrue(error is PluginError.CameraControlError)
            error as PluginError.CameraControlError
            assertEquals(CameraControlOperation.ZOOM, error.operation)
            assertEquals(42, error.viewId)
        } finally { f.close() }
    }

    @Test
    fun `native focus does not cancel an outstanding Dart control`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            val pending = CompletableDeferred<Unit>()
            f.zoom += pending
            val command = f.calls.async { f.scanner.setZoomRatio(2F) }
            f.scanner.focus(100, 0F, 0F)
            assertFalse(pending.isCancelled)
            verify(f.camera, never()).focus(anyLong(), anyFloat(), anyFloat())
            pending.complete(Unit)
            command.await()
        } finally { f.close() }
    }

    @Test
    fun `new focus gesture supersedes a pending gesture without restarting preview`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            clearInvocations(f.camera)
            val pending = CompletableDeferred<Unit>()
            doReturn(pending, CompletableDeferred(Unit)).`when`(f.camera).focus(anyLong(), anyFloat(), anyFloat())
            f.scanner.focus(500L, 10F, 20F)
            f.scanner.focus(500L, 30F, 40F)
            assertTrue(pending.isCancelled)
            verify(f.camera).focus(500L, 30F, 40F)
            verify(f.camera, never()).resetFocus()
            verify(f.camera, never()).setZoomRatio(anyFloat())
            verify(f.camera, never()).setTorch(anyBoolean())
        } finally { f.close() }
    }

    @Test
    fun `release during SDK call cancels the returned future before admitting another control`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val pending = CompletableDeferred<Unit>()
            doAnswer {
                f.scanner.releaseCamera()
                pending
            }.`when`(f.camera).setZoomRatio(anyFloat())
            val capture = f.capture()
            f.open()
            capture.await()
            assertTrue(pending.isCancelled)
            verify(f.camera, never()).setTorch(false)
            verify(f.first, never()).bindFocus()
        } finally { f.close() }
    }

    @Test
    fun `SDK completion from worker thread preserves original control failure`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            val pending = CompletableDeferred<Unit>()
            val failure = IllegalStateException("SDK failure")
            f.zoom += pending
            val result = f.calls.async { runCatching { f.scanner.setZoomRatio(2F) }.exceptionOrNull() }
            withContext(Dispatchers.Default) { pending.completeExceptionally(failure) }
            val error = result.await() as PluginError.CameraControlError
            assertSame(failure, error.cause)
            assertEquals(CameraControlOperation.ZOOM, error.operation)
        } finally { f.close() }
    }

    @Test
    fun `SDK cancellation is a control error when the caller is still active`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.zoom += CompletableDeferred<Unit>().apply { cancel() }
            val error = runCatching { f.scanner.setZoomRatio(2F) }.exceptionOrNull()
            assertTrue(error is PluginError.CameraControlError)
            assertEquals(CameraControlOperation.ZOOM, (error as PluginError.CameraControlError).operation)
        } finally { f.close() }
    }

    @Test
    fun `focus uses current preview dimensions and offsets`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            val preview = f.camera.previewView
            doReturn(100).`when`(preview).width
            doReturn(200).`when`(preview).height
            doReturn(CompletableDeferred(Unit)).`when`(f.camera).focus(anyLong(), anyFloat(), anyFloat())

            f.scanner.focus(3000L, 12F, -34F)
            verify(f.camera).focus(3000L, 62F, 66F)

            doReturn(101).`when`(preview).width
            doReturn(205).`when`(preview).height
            f.scanner.focus(3000L, 12F, -34F)
            verify(f.camera).focus(3000L, 62.5F, 68.5F)
        } finally { f.close() }
    }

    @Test
    fun `repeated release does not postpone native cleanup`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.releaseCamera()
            f.scanner.releaseCamera()

            assertNull(f.scanner.viewId)
            assertEquals(listOf(300L), f.delays)
            f.timers.single().run()
            verify(f.camera).dispose()
            verify(f.analyzer).dispose()
        } finally { f.close() }
    }

    @Test
    fun `release cancels a focus gesture before its SDK dispatch`() = runBlocking<Unit> {
        val queued = ArrayDeque<Runnable>()
        var deferDispatch = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (deferDispatch) queued += block else block.run()
            }
        }
        val f = Fixture(dispatcher)
        try {
            f.activate()
            deferDispatch = true
            f.scanner.focus(1000L, 0F, 0F)
            f.scanner.releaseCamera()
            f.scanner.select(f.second)
            while (queued.isNotEmpty()) queued.removeFirst().run()
            verify(f.camera, never()).focus(anyLong(), anyFloat(), anyFloat())
            assertEquals(43, f.scanner.viewId)
        } finally { f.close() }
    }

    @Test
    fun `queued focus cannot cancel a Dart control admitted before the gesture runs`() = runBlocking<Unit> {
        val queued = ArrayDeque<Runnable>()
        var deferDispatch = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (deferDispatch) queued += block else block.run()
            }
        }
        val f = Fixture(dispatcher)
        try {
            f.activate()
            deferDispatch = true
            f.scanner.focus(1000L, 0F, 0F)
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val control = f.calls.async { f.scanner.setZoomRatio(2F) }
            while (queued.isNotEmpty()) queued.removeFirst().run()

            verify(f.camera, never()).focus(anyLong(), anyFloat(), anyFloat())
            assertFalse(zoom.isCancelled)
            assertFalse(control.isCompleted)
            zoom.complete(Unit)
            while (queued.isNotEmpty()) queued.removeFirst().run()
            control.await()
        } finally { f.close() }
    }

    private class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
        val camera = mock(Camera::class.java)
        val analyzer = mock(ImageBarcodeAnalyzer::class.java)
        val connection = CameraConnection()
        val host = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        val boundOwners = mutableListOf<LifecycleOwner>()
        val handler = mock(Handler::class.java)
        val first = view(42)
        val second = view(43)
        val calls = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val posts = ArrayDeque<Runnable>()
        val timers = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        val zoom = ArrayDeque<Deferred<Unit>>()
        val results = mutableListOf<Pair<Int, Barcode>>()
        val released = mutableListOf<Throwable>()
        lateinit var onFrame: OnCameraFrame
        lateinit var initialized: () -> Unit
        lateinit var availability: (CameraAvailability) -> Unit
        lateinit var failed: (Exception) -> Unit
        lateinit var previewReady: () -> Unit
        val scanner: Scanner

        init {
            doReturn(mock(View::class.java)).`when`(camera).previewView
            doAnswer { posts += it.getArgument<Runnable>(0); true }.`when`(handler).post(anyValue())
            doAnswer {
                timers += it.getArgument<Runnable>(0); delays += it.getArgument<Long>(1); true
            }.`when`(handler).postDelayed(anyValue(), anyLong())
            doAnswer { timers.remove(it.getArgument<Runnable>(0)); null }.`when`(handler).removeCallbacks(anyValue())
            doReturn(CompletableDeferred(Unit)).`when`(camera).resetFocus()
            doReturn(CompletableDeferred(Unit)).`when`(camera).setTorch(anyBoolean())
            doAnswer { zoom.removeFirstOrNull() ?: CompletableDeferred(Unit) }.`when`(camera).setZoomRatio(anyFloat())
            doAnswer {
                boundOwners += it.getArgument<LifecycleOwner>(0)
                onFrame = it.getArgument(2); availability = it.getArgument(3); initialized = it.getArgument(4); failed = it.getArgument(5)
                null
            }.`when`(camera).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
            doReturn(BARCODE).`when`(analyzer).analyze(anyValue(), anyValue())
            for (target in listOf(first, second)) {
                doAnswer { previewReady = it.getArgument(1); null }.`when`(target).attachPreview(anyValue(), anyValue())
            }
            scanner = Scanner(camera, analyzer, handler, { _, cause -> released += cause },
                { id, barcode -> results += id to barcode }, CoroutineScope(SupervisorJob() + dispatcher),
                connection)
            scanner.attachActivity(host.lifecycle)
        }

        fun capture(permission: suspend () -> Boolean = { true }): Deferred<Unit> {
            scanner.select(first)
            return calls.async { scanner.capture(ScannerConfiguration(), permission = permission) }
        }

        suspend fun activate() {
            val capture = capture()
            open()
            capture.await()
        }

        fun emitFrame() {
            val frame = mock(CameraFrame::class.java)
            doReturn(Rect(0, 0, 100, 100)).`when`(frame).cropRect
            onFrame(frame)
        }
        fun open() { initialized(); availability(CameraAvailability.Open) }
        fun drain() { while (posts.isNotEmpty()) posts.removeFirst().run() }
        fun close() { scanner.dispose(); calls.cancel() }

        private fun view(id: Int) = mock(ScannerView::class.java).also {
            doReturn(id).`when`(it).viewId
            doReturn(true).`when`(it).isPreviewReady()
        }
    }

    private companion object {
        val BARCODE = Barcode("value", "value", 1, 1)
        fun <T> anyValue(): T = any<T>()
    }
}
