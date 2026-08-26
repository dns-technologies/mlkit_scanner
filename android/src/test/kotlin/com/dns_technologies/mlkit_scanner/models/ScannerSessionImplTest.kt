package com.dns_technologies.mlkit_scanner.models

import android.os.Handler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

internal class ScannerSessionImplTest {
    @Test
    fun `newest view hosts shared preview without disposing previous view`() {
        val fixture = Fixture()
        val first = fixture.view(FIRST_VIEW_ID)
        val second = fixture.attach(SECOND_VIEW_ID)

        verify(first).detachPreview()
        verify(second).attachPreview(anyValue())
        assertFalse(fixture.hasPreview(FIRST_VIEW_ID))
        assertTrue(fixture.hasPreview(SECOND_VIEW_ID))
        verify(first, never()).disposeFromSession()
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `disposing view without preview does not disturb preview host`() {
        val fixture = Fixture()
        val first = fixture.view(FIRST_VIEW_ID)
        val second = fixture.attach(SECOND_VIEW_ID)
        clearInvocations(second)

        fixture.session.disposeView(FIRST_VIEW_ID)

        verify(first).disposeFromSession()
        verify(second, never()).detachPreview()
        verify(second, never()).attachPreview(anyValue())
        assertTrue(fixture.delayedCallbacks.isEmpty())
    }

    @Test
    fun `disposing preview host moves preview to newest remaining view`() {
        val fixture = Fixture()
        val first = fixture.view(FIRST_VIEW_ID)
        val second = fixture.attach(SECOND_VIEW_ID)

        fixture.session.disposeView(SECOND_VIEW_ID)

        verify(second).disposeFromSession()
        verify(first, times(2)).attachPreview(anyValue())
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `last view schedules release and a new view cancels it`() {
        val fixture = Fixture()

        fixture.session.disposeView(FIRST_VIEW_ID)
        val releaseTask = fixture.delayedCallbacks.single()
        fixture.attach(SECOND_VIEW_ID)
        releaseTask.run()

        verify(fixture.mainHandler).removeCallbacks(releaseTask)
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `session releases shared pipeline after registry remains empty`() {
        val fixture = Fixture()
        assertEquals(1, fixture.hostLifecycleOwner.observerCount)

        fixture.session.disposeView(FIRST_VIEW_ID)
        fixture.delayedCallbacks.single().run()

        verify(fixture.scanner).dispose()
        assertEquals(0, fixture.hostLifecycleOwner.observerCount)
        assertEquals(1, fixture.subscriptionCancelCalls)
        assertEquals(1, fixture.releaseCalls)
    }

    @Test
    fun `navigation gap pauses camera and scan while retaining shared resources`() =
        runSessionTest {
            val fixture = Fixture()
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.startCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            fixture.session.startScan(FIRST_VIEW_ID, 100)
            clearInvocations(fixture.scanner)

            fixture.session.disposeView(FIRST_VIEW_ID)

            assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
            assertEquals(
                listOf(ScannerSessionImpl.NAVIGATION_GRACE_PERIOD_MS),
                fixture.scheduledDelays,
            )
            verify(fixture.scanner, atLeastOnce()).pauseScan()
            verify(fixture.scanner, never()).dispose()
        }

    @Test
    fun `new view during grace reuses camera and waits for preview before starting its scan`() =
        runSessionTest {
            val fixture = Fixture()
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.startCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            fixture.session.startScan(FIRST_VIEW_ID, 100)
            fixture.session.disposeView(FIRST_VIEW_ID)
            val releaseTask = fixture.delayedCallbacks.single()
            clearInvocations(fixture.scanner)

            fixture.attach(SECOND_VIEW_ID, previewReady = false)

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner, never()).resumeScan()
            verify(fixture.mainHandler).removeCallbacks(releaseTask)

            fixture.session.startCamera(SECOND_VIEW_ID, 0.75, null)
            fixture.session.startScan(SECOND_VIEW_ID, 100)

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner, never()).resumeScan()

            fixture.markPreviewReady(SECOND_VIEW_ID)

            verify(fixture.scanner).resumeScan()
            releaseTask.run()

            assertEquals(1, fixture.startCalls)
            verify(fixture.scanner, never()).dispose()
        }

    @Test
    fun `late camera pause from covered view does not pause current view`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, null, null)
        }
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        fixture.attach(SECOND_VIEW_ID)
        clearInvocations(fixture.scanner)

        fixture.session.pauseCamera(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, never()).pauseScan()

        fixture.session.startCamera(SECOND_VIEW_ID, null, null)
        fixture.session.startScan(SECOND_VIEW_ID, 100)
        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.markPreviewReady(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, atLeastOnce()).pauseScan()
        clearInvocations(fixture.scanner)

        fixture.session.resumeCamera(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `scan pause belongs to its view and is restored when that view returns`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, null, null)
        }
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        fixture.attach(SECOND_VIEW_ID)
        fixture.session.startCamera(SECOND_VIEW_ID, null, null)
        fixture.session.startScan(SECOND_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.pauseScan(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, never()).pauseScan()

        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.markPreviewReady(FIRST_VIEW_ID)

        verify(fixture.scanner, atLeastOnce()).pauseScan()
        clearInvocations(fixture.scanner)

        fixture.session.startScan(FIRST_VIEW_ID, 100)

        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `camera and scan commands always update shared session`() = runSessionTest {
        val fixture = Fixture()
        fixture.attach(SECOND_VIEW_ID)
        fixture.setCameraActive(true)
        fixture.session.startCamera(SECOND_VIEW_ID, null, null)
        fixture.completeZoom()
        val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)
        clearInvocations(fixture.scanner)

        fixture.session.setZoom(0.75F)
        fixture.session.updateScanPeriod(250)
        fixture.session.setCropArea(cropRect)
        fixture.session.startScan(SECOND_VIEW_ID, 400)
        fixture.session.toggleFlashLight()

        verify(fixture.scanner).setZoom(0.75F)
        verify(fixture.scanner).updateScanPeriod(250)
        verify(fixture.scanner).updateScanPeriod(400)
        verify(fixture.view(SECOND_VIEW_ID)).renderCropArea(cropRect)
        verify(fixture.scanner).resumeScan()
        verify(fixture.scanner).toggleFlashLight()
    }

    @Test
    fun `runtime zoom is rejected before camera initialization`() = runSessionTest {
        val fixture = Fixture()
        clearInvocations(fixture.scanner)

        val error = runCatching { fixture.session.setZoom(0.5F) }.exceptionOrNull()

        assertSame(PluginError.CameraIsNotInitialized, error)
        verify(fixture.scanner, never()).setZoom(0.5F)
    }

    @Test
    fun `runtime zoom does not report success after session release`() = runSessionTest {
        val fixture = Fixture()
        fixture.setCameraActive(true)
        val zoom = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoom(0.5F)
        }

        fixture.session.release()
        fixture.completeZoom()

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { zoom.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
    }

    @Test
    fun `parallel torch toggles execute sequentially`() = runSessionTest {
        val fixture = Fixture()
        fixture.setCameraActive(true)
        val firstCompletion = CompletableDeferred<Unit>()
        fixture.enqueueTorchResult(firstCompletion)
        clearInvocations(fixture.scanner)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.toggleFlashLight()
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.toggleFlashLight()
        }

        verify(fixture.scanner, times(1)).toggleFlashLight()
        assertFalse(second.isCompleted)

        firstCompletion.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        verify(fixture.scanner, times(2)).toggleFlashLight()
    }

    @Test
    fun `parallel zoom updates execute sequentially`() = runSessionTest {
        val fixture = Fixture()
        fixture.setCameraActive(true)
        val firstCompletion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(firstCompletion)
        clearInvocations(fixture.scanner)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoom(0.25F)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoom(0.75F)
        }

        verify(fixture.scanner, times(1)).setZoom(0.25F)
        verify(fixture.scanner, never()).setZoom(0.75F)
        assertFalse(second.isCompleted)

        firstCompletion.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { first.await() }
        verify(fixture.scanner).setZoom(0.75F)
        fixture.completeZoom()
        withTimeout(TEST_TIMEOUT_MS) { second.await() }
    }

    @Test
    fun `new preview renders crop retained by scanner`() {
        val fixture = Fixture()
        fixture.setCameraActive(true)
        val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)

        fixture.session.setCropArea(cropRect)
        val second = fixture.attach(SECOND_VIEW_ID)

        verify(second).renderCropArea(cropRect)
    }

    @Test
    fun `preview host replacement cancels result captured by previous view`() {
        val received = mutableListOf<Pair<Int, Barcode>>()
        val fixture = Fixture { viewId, barcode -> received += viewId to barcode }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)

        fixture.emitScanResult(BARCODE)
        fixture.attach(SECOND_VIEW_ID)
        fixture.postedCallbacks.single().run()

        assertTrue(received.isEmpty())
        verify(fixture.mainHandler).removeCallbacks(fixture.postedCallbacks.single())
    }

    @Test
    fun `only current view receives results when multiple views requested scanning`() {
        val received = mutableListOf<Pair<Int, Barcode>>()
        val fixture = Fixture { viewId, barcode -> received += viewId to barcode }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)

        fixture.emitScanResult(BARCODE)
        fixture.postedCallbacks.last().run()

        assertEquals(listOf(SECOND_VIEW_ID to BARCODE), received)

        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.markPreviewReady(FIRST_VIEW_ID)
        fixture.emitScanResult(BARCODE)
        fixture.postedCallbacks.last().run()

        assertEquals(
            listOf(SECOND_VIEW_ID to BARCODE, FIRST_VIEW_ID to BARCODE),
            received,
        )
    }

    @Test
    fun `lifecycle commands for disposed view do not affect current view`() {
        val fixture = Fixture()
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)
        fixture.session.disposeView(FIRST_VIEW_ID)
        clearInvocations(fixture.scanner)

        fixture.session.pauseCamera(FIRST_VIEW_ID)
        fixture.session.pauseScan(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, never()).pauseScan()
    }

    @Test
    fun `disposing one of several views keeps queued shared result`() {
        val received = mutableListOf<Barcode>()
        val fixture = Fixture { _, barcode -> received.add(barcode) }
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()

        fixture.session.disposeView(FIRST_VIEW_ID)
        delivery.run()

        assertEquals(listOf(BARCODE), received)
        verify(fixture.mainHandler, never()).removeCallbacks(delivery)
    }

    @Test
    fun `disposing preview host cancels result captured by that view`() {
        val received = mutableListOf<Pair<Int, Barcode>>()
        val fixture = Fixture { viewId, barcode -> received += viewId to barcode }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(SECOND_VIEW_ID)
        delivery.run()

        assertTrue(received.isEmpty())
        verify(fixture.mainHandler).removeCallbacks(delivery)
        verify(fixture.scanner, atLeastOnce()).pauseScan()

        verify(fixture.scanner, never()).resumeScan()
        fixture.markPreviewReady(FIRST_VIEW_ID)

        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `disposing one of several views does not pause shared scan`() {
        val fixture = Fixture()
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(FIRST_VIEW_ID)

        verify(fixture.scanner, never()).pauseScan()
    }

    @Test
    fun `new view after navigation gap does not inherit removed view scan intent`() {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)

        verify(fixture.scanner, never()).resumeScan()
        verify(fixture.scanner, never()).updateScanPeriod(anyInt())
    }

    @Test
    fun `disposing last view cancels queued shared result`() {
        val received = mutableListOf<Barcode>()
        val fixture = Fixture { _, barcode -> received.add(barcode) }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()

        fixture.session.disposeView(FIRST_VIEW_ID)
        delivery.run()

        verify(fixture.mainHandler).removeCallbacks(delivery)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `camera pause cancels queued result and resume restores requested scan`() {
        val received = mutableListOf<Barcode>()
        val fixture = Fixture { _, barcode -> received.add(barcode) }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()
        clearInvocations(fixture.scanner)

        fixture.session.pauseCamera(FIRST_VIEW_ID)
        delivery.run()

        verify(fixture.mainHandler).removeCallbacks(delivery)
        verify(fixture.scanner).pauseScan()
        assertTrue(received.isEmpty())

        clearInvocations(fixture.scanner)
        fixture.session.resumeCamera(FIRST_VIEW_ID)

        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `repeated camera initialization does not clear view pause`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.pauseCamera(FIRST_VIEW_ID)

        fixture.session.startCamera(FIRST_VIEW_ID, null, null)

        assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
    }

    @Test
    fun `camera lifecycle stays created when session attaches to already paused host`() =
        runSessionTest {
            val fixture = Fixture(hostLifecycleState = Lifecycle.State.STARTED)
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.startCamera(FIRST_VIEW_ID, null, null)
            }

            assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            clearInvocations(fixture.scanner)
            fixture.session.startScan(FIRST_VIEW_ID, 0)
            verify(fixture.scanner).pauseScan()

            clearInvocations(fixture.scanner)
            fixture.hostLifecycleOwner.moveTo(Lifecycle.State.RESUMED)

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner).resumeScan()
        }

    @Test
    fun `host lifecycle replacement pauses during detach and ignores old host afterwards`() =
        runSessionTest {
            val fixture = Fixture()
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.startCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            fixture.session.startScan(FIRST_VIEW_ID, 0)
            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)

            clearInvocations(fixture.scanner)
            fixture.session.detachHostLifecycle()

            assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
            assertEquals(0, fixture.hostLifecycleOwner.observerCount)
            verify(fixture.scanner).pauseScan()

            val replacementHost = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
            fixture.session.attachHostLifecycle(replacementHost.lifecycle)
            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)

            clearInvocations(fixture.scanner)
            fixture.hostLifecycleOwner.moveTo(Lifecycle.State.CREATED)

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner, never()).pauseScan()

        }

    @Test
    fun `parallel starts share one camera initialization`() = runSessionTest {
        val fixture = Fixture()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, null, null)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, null, null)
        }

        assertEquals(1, fixture.startCalls)
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        Unit
    }

    @Test
    fun `parallel starts use only first initial zoom and crop`() = runSessionTest {
        val fixture = Fixture()
        val firstCrop = RecognizeVisorCropRect(scaleWidth = 0.25)
        val secondCrop = RecognizeVisorCropRect(scaleWidth = 0.75)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(
                FIRST_VIEW_ID,
                0.25,
                firstCrop,
            )
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(
                FIRST_VIEW_ID,
                0.75,
                secondCrop,
            )
        }

        assertEquals(1, fixture.startCalls)
        verify(fixture.scanner).setCropArea(firstCrop)
        verify(fixture.scanner, never()).setCropArea(secondCrop)
        verify(fixture.scanner, never()).setZoom(anyFloat())

        fixture.completeInitialization()
        verify(fixture.scanner).setZoom(0.25F)
        verify(fixture.scanner, never()).showPreview()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        fixture.completeZoom()
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        verify(fixture.scanner).showPreview()
        Unit
    }

    @Test
    fun `start on initialized camera ignores new initial zoom and crop`() = runSessionTest {
        val fixture = Fixture()
        val firstCrop = RecognizeVisorCropRect(scaleWidth = 0.25)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(
                FIRST_VIEW_ID,
                0.25,
                firstCrop,
            )
        }
        fixture.completeInitialization()
        fixture.completeZoom()
        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        clearInvocations(fixture.scanner)

        fixture.session.startCamera(
            FIRST_VIEW_ID,
            0.75,
            RecognizeVisorCropRect(scaleWidth = 0.75),
        )

        assertEquals(1, fixture.startCalls)
        verify(fixture.scanner, never()).setCropArea(anyValue())
        verify(fixture.scanner, never()).setZoom(anyFloat())
    }

    @Test
    fun `release fails pending initialization and disposes resources once`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, null, null)
        }

        fixture.session.release()
        fixture.session.release()

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
        verify(fixture.scanner).dispose()
        assertEquals(1, fixture.subscriptionCancelCalls)
        assertEquals(1, fixture.releaseCalls)
    }

    @Test
    fun `initialization error releases session and is propagated`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, null, null)
        }
        val expectedError = IllegalStateException("initialization failed")

        fixture.failInitialization(expectedError)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(expectedError.message, error?.message)
        verify(fixture.scanner).dispose()
    }

    @Test
    fun `initial zoom error keeps preview hidden and fails initialization`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, 0.5, null)
        }
        val expectedError = IllegalStateException("zoom failed")
        fixture.completeInitialization()

        fixture.failZoom(expectedError)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertEquals(expectedError.message, error?.message)
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner).dispose()
    }

    @Test
    fun `release while initial zoom is pending keeps preview hidden`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.startCamera(FIRST_VIEW_ID, 0.5, null)
        }
        fixture.completeInitialization()

        fixture.session.release()
        fixture.completeZoom()

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner).dispose()
    }

    @Test
    fun `start after release fails without restarting camera`() = runSessionTest {
        val fixture = Fixture()
        fixture.session.release()

        val error = runCatching {
            fixture.session.startCamera(FIRST_VIEW_ID, null, null)
        }.exceptionOrNull()

        assertSame(PluginError.CameraSessionDisposed, error)
        assertEquals(0, fixture.startCalls)
    }

    private fun runSessionTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { supervisorScope { block() } }
    }

    private class Fixture(
        hostLifecycleState: Lifecycle.State = Lifecycle.State.RESUMED,
        onScanResult: (Int, Barcode) -> Unit = { _, _ -> },
    ) {
        val scanner: Scanner = mock(Scanner::class.java)
        val mainHandler: Handler = mock(Handler::class.java)
        val postedCallbacks = mutableListOf<Runnable>()
        val delayedCallbacks = mutableListOf<Runnable>()
        val scheduledDelays = mutableListOf<Long>()
        val hostLifecycleOwner = TestHostLifecycleOwner(hostLifecycleState)
        val session: ScannerSessionImpl
        var startCalls = 0
            private set
        var subscriptionCancelCalls = 0
            private set
        var releaseCalls = 0
            private set

        private val views = mutableMapOf<Int, ScannerView>()
        private val previewState = mutableMapOf<Int, Boolean>()
        private val previewReadyState = mutableMapOf<Int, Boolean>()
        private val previewReadyCallbacks = mutableMapOf<Int, () -> Unit>()
        private var isCameraActive = false
        private var onReady: (() -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null
        private var scanResultListener: ((Barcode) -> Unit)? = null
        private var currentCropArea: RecognizeVisorCropRect? = null
        private val zoomCompletion = CompletableDeferred<Unit>()
        private val zoomResults = ArrayDeque<CompletableDeferred<Unit>>()
        private val torchResults = ArrayDeque<CompletableDeferred<Unit>>()

        init {
            doAnswer { invocation ->
                postedCallbacks += invocation.getArgument<Runnable>(0)
                true
            }.`when`(mainHandler).post(anyValue())
            doAnswer { invocation ->
                delayedCallbacks += invocation.getArgument<Runnable>(0)
                scheduledDelays += invocation.getArgument<Long>(1)
                true
            }.`when`(mainHandler).postDelayed(anyValue(), anyLong())
            doAnswer { isCameraActive }.`when`(scanner).isActive()
            doAnswer { currentCropArea }.`when`(scanner).currentCropArea
            doAnswer { invocation ->
                currentCropArea = invocation.getArgument(0)
                null
            }.`when`(scanner).setCropArea(anyValue())
            doAnswer { invocation ->
                scanResultListener = invocation.getArgument(0)
                ScanResultSubscription { subscriptionCancelCalls += 1 }
            }.`when`(scanner).subscribeToScanResults(anyValue())
            doAnswer { invocation ->
                startCalls += 1
                onReady = invocation.getArgument(1)
                onError = invocation.getArgument(2)
                null
            }.`when`(scanner).startCamera(anyValue(), anyValue(), anyValue())
            doAnswer {
                zoomResults.removeFirstOrNull() ?: zoomCompletion
            }.`when`(scanner).setZoom(anyFloat())
            doAnswer {
                torchResults.removeFirstOrNull() ?: CompletableDeferred(Unit)
            }.`when`(scanner).toggleFlashLight()
            doAnswer {
                isCameraActive = false
                null
            }.`when`(scanner).dispose()

            session = ScannerSessionImpl(
                scanner = scanner,
                mainHandler = mainHandler,
                onScanResult = onScanResult,
                onReleased = { releaseCalls += 1 },
                initializationScope = CoroutineScope(Dispatchers.Unconfined),
                lifecycleRegistryFactory = LifecycleRegistry::createUnsafe,
            )
            session.attachHostLifecycle(hostLifecycleOwner.lifecycle)
            attach(FIRST_VIEW_ID)
        }

        fun attach(viewId: Int, previewReady: Boolean = true): ScannerView {
            val view = mock(ScannerView::class.java)
            previewState[viewId] = false
            previewReadyState[viewId] = false
            doAnswer { invocation ->
                previewState.keys.forEach { currentViewId ->
                    previewState[currentViewId] = false
                    previewReadyState[currentViewId] = false
                }
                previewState[viewId] = true
                previewReadyState[viewId] = false
                previewReadyCallbacks[viewId] = invocation.getArgument(0)
                null
            }.`when`(view).attachPreview(anyValue())
            doAnswer {
                clearPreview(viewId)
                null
            }.`when`(view).detachPreview()
            doAnswer {
                clearPreview(viewId)
                null
            }.`when`(view).disposeFromSession()
            doAnswer { previewState[viewId] == true }.`when`(view).hasPreview()
            doAnswer {
                previewState[viewId] == true && previewReadyState[viewId] == true
            }.`when`(view).isPreviewReady()
            views[viewId] = view
            session.attachView(viewId, view)
            if (previewReady) markPreviewReady(viewId)
            return view
        }

        fun markPreviewReady(viewId: Int) {
            previewReadyState[viewId] = true
            previewReadyCallbacks.remove(viewId)?.invoke()
        }

        fun view(viewId: Int): ScannerView = views.getValue(viewId)

        fun hasPreview(viewId: Int): Boolean = previewState[viewId] == true

        private fun clearPreview(viewId: Int) {
            previewState[viewId] = false
            previewReadyState[viewId] = false
            previewReadyCallbacks.remove(viewId)
        }

        fun emitScanResult(result: Barcode) {
            scanResultListener?.invoke(result)
        }

        fun setCameraActive(value: Boolean) {
            isCameraActive = value
        }

        fun activateCamera(viewId: Int) {
            isCameraActive = true
            runBlocking { session.startCamera(viewId, null, null) }
        }

        fun completeInitialization() {
            isCameraActive = true
            onReady?.invoke()
        }

        fun completeZoom() {
            zoomCompletion.complete(Unit)
        }

        fun enqueueZoomResult(result: CompletableDeferred<Unit>) {
            zoomResults += result
        }

        fun failZoom(error: Throwable) {
            zoomCompletion.completeExceptionally(error)
        }

        fun failInitialization(error: Exception) {
            onError?.invoke(error)
        }

        fun enqueueTorchResult(result: CompletableDeferred<Unit>) {
            torchResults += result
        }
    }

    private class TestHostLifecycleOwner(
        initialState: Lifecycle.State,
    ) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = initialState
        }

        override val lifecycle: Lifecycle
            get() = registry

        val observerCount: Int
            get() = registry.observerCount

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private companion object {
        const val FIRST_VIEW_ID = 11
        const val SECOND_VIEW_ID = 22
        const val TEST_TIMEOUT_MS = 1_000L
        val BARCODE = Barcode(
            rawValue = "1234567890",
            displayValue = "1234567890",
            format = 1,
            valueType = 1,
        )

        fun <T> anyValue(): T = any<T>()
    }
}
