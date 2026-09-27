package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.mlkit

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode as MlkitBarcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

// region MlkitImageBarcodeAnalyzerTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class MlkitImageBarcodeAnalyzerTest {
    private val analyzers = mutableListOf<MlkitImageBarcodeAnalyzer>()

    @After
    fun disposeAnalyzers() {
        analyzers.forEach { it.dispose() }
    }

    @Test
    fun `null raw values are skipped and result traversal stops at first usable barcode`() {
        val missing = mock(MlkitBarcode::class.java)
        val valid = mock(MlkitBarcode::class.java)
        val tail = mock(MlkitBarcode::class.java)
        doReturn(BARCODE_VALUE).`when`(valid).rawValue
        val analyzer = analyzer(barcodeScanner(listOf(missing, valid, tail)))

        assertEquals(BARCODE_VALUE, analyzer.analyzeOnWorker(FakeFrame(), null)?.rawValue)
        verify(tail, never()).rawValue
    }

    @Test
    fun `only null raw values produces no barcode`() {
        val analyzer = analyzer(barcodeScanner(listOf(mock(MlkitBarcode::class.java))))

        assertNull(analyzer.analyzeOnWorker(FakeFrame(), null))
    }

    @Test
    fun `empty raw value remains valid and display value stays nullable`() {
        val barcode = mock(MlkitBarcode::class.java)
        doReturn("").`when`(barcode).rawValue
        val analyzer = analyzer(barcodeScanner(listOf(barcode)))

        val result = analyzer.analyzeOnWorker(FakeFrame(), null)
        assertEquals("", result?.rawValue)
        assertNull(result?.displayValue)
    }

    @Test
    fun `completed task failure returns no result and logs the failure`() {
        val scanner = barcodeScanner()
        doReturn(Tasks.forException<List<MlkitBarcode>>(IllegalStateException("Recognition failed")))
            .`when`(scanner).process(anyValue<InputImage>())
        val errors = mutableListOf<String>()
        val analyzer = analyzer(scanner, logError = errors::add)

        assertNull(analyzer.analyzeOnWorker(FakeFrame(), null))
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("Recognition failed"))
    }

    @Test
    fun `cancelled task returns no result and does not strand later analysis`() {
        val cancellation = CancellationTokenSource()
        val task = TaskCompletionSource<List<MlkitBarcode>>(cancellation.token)
        cancellation.cancel()
        ShadowLooper.idleMainLooper()
        assertTrue(task.task.isCanceled)
        val scanner = barcodeScanner()
        doReturn(task.task).`when`(scanner).process(anyValue<InputImage>())
        val clock = MutableClock()
        val errors = mutableListOf<String>()
        val analyzer = analyzer(scanner, currentTimeMs = clock::read, logError = errors::add)
        assertNull(analyzer.analyzeOnWorker(FakeFrame(), null))
        assertEquals(1, errors.size)

        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS
        doReturn(Tasks.forResult(emptyList<MlkitBarcode>())).`when`(scanner).process(anyValue<InputImage>())
        val next = FakeFrame()
        assertNull(analyzer.analyzeOnWorker(next, null))
        assertEquals(1, next.accessCalls)
    }

    @Test
    fun `production InputImage factory preserves NV21 dimensions and rotation`() {
        // Robolectric does not run the app's ML Kit initialization provider here.
        MlKit.initialize(RuntimeEnvironment.getApplication())
        val scanner = barcodeScanner()
        var received: InputImage? = null
        doAnswer {
            received = it.getArgument(0)
            Tasks.forResult(emptyList<MlkitBarcode>())
        }.`when`(scanner).process(anyValue<InputImage>())
        val analyzer = MlkitImageBarcodeAnalyzer(scanner, { 0L }, {}).also { analyzers += it }

        analyzer.analyzeOnWorker(FakeFrame(), null)

        assertEquals(FRAME_WIDTH, received?.width)
        assertEquals(FRAME_HEIGHT, received?.height)
        assertEquals(ROTATION_DEGREES, received?.rotationDegrees)
    }

    @Test
    fun `nv21 roi is created with mlkit format and processed`() {
        val scanner = barcodeScanner()
        val inputImage = mock(InputImage::class.java)
        val bytes = ByteArray(24)
        var receivedBytes: ByteArray? = null
        var receivedMetadata: List<Int>? = null
        val analyzer = analyzer(
            scanner = scanner,
            fromByteArray = { data, width, height, rotation, format ->
                receivedBytes = data
                receivedMetadata = listOf(width, height, rotation, format)
                inputImage
            },
        )
        val cropRect = Rect(2, 0, 6, 4)
        val frame = FakeFrame(nv21Bytes = bytes)

        analyzer.analyzeOnWorker(frame, cropRect)

        assertSame(bytes, receivedBytes)
        assertEquals(
            listOf(cropRect.width, cropRect.height, ROTATION_DEGREES, InputImage.IMAGE_FORMAT_NV21),
            receivedMetadata,
        )
        assertEquals(cropRect, frame.receivedCropRect)
        verify(scanner).process(inputImage)
    }

    @Test
    fun `missed recognition is retried after the time interval`() {
        val scanner = barcodeScanner()
        val clock = MutableClock()
        val analyzer = analyzer(scanner, currentTimeMs = clock::read)
        val frames = List(3) { FakeFrame() }

        analyzer.analyzeOnWorker(frames[0], null)
        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS - 1
        analyzer.analyzeOnWorker(frames[1], null)
        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS
        analyzer.analyzeOnWorker(frames[2], null)

        assertEquals(listOf(1, 0, 1), frames.map(FakeFrame::accessCalls))
    }

    @Test
    fun `recognized mlkit barcode maps every scanner barcode field`() {
        val mlkitBarcode = mock(MlkitBarcode::class.java)
        doReturn(BARCODE_VALUE).`when`(mlkitBarcode).rawValue
        doReturn(DISPLAY_VALUE).`when`(mlkitBarcode).displayValue
        doReturn(MlkitBarcode.FORMAT_QR_CODE).`when`(mlkitBarcode).format
        doReturn(MlkitBarcode.TYPE_URL).`when`(mlkitBarcode).valueType
        val errors = mutableListOf<String>()
        val analyzer = analyzer(barcodeScanner(listOf(mlkitBarcode)), logError = errors::add)

        val result = analyzer.analyzeOnWorker(FakeFrame(), null)

        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(
            mapOf(
                "raw_value" to BARCODE_VALUE,
                "display_value" to DISPLAY_VALUE,
                "format" to MlkitBarcode.FORMAT_QR_CODE,
                "value_type" to MlkitBarcode.TYPE_URL,
            ),
            result?.toMap(),
        )
    }

    @Test
    fun `unknown mlkit format maps to dart unknown format code`() {
        val mlkitBarcode = mock(MlkitBarcode::class.java)
        doReturn(BARCODE_VALUE).`when`(mlkitBarcode).rawValue
        doReturn(MlkitBarcode.FORMAT_UNKNOWN).`when`(mlkitBarcode).format
        doReturn(MlkitBarcode.TYPE_UNKNOWN).`when`(mlkitBarcode).valueType
        val analyzer = analyzer(barcodeScanner(listOf(mlkitBarcode)))

        val result = analyzer.analyzeOnWorker(FakeFrame(), null)

        assertEquals(UNKNOWN_FORMAT_CODE, result?.toMap()?.get("format"))
        assertEquals(null, result?.toMap()?.get("display_value"))
    }

    @Test
    fun `mlkit failure without message is still logged`() {
        val errors = mutableListOf<String>()
        val scanner = barcodeScanner()
        doThrow(IllegalStateException()).`when`(scanner).process(anyValue<InputImage>())
        val analyzer = analyzer(scanner = scanner, logError = errors::add)

        val result = analyzer.analyzeOnWorker(FakeFrame(), null)

        assertEquals(null, result)
        assertEquals(listOf("IllegalStateException"), errors)
    }

    @Test
    fun `dispose closes barcode scanner once`() {
        val scanner = barcodeScanner()
        val analyzer = analyzer(scanner)

        analyzer.dispose()
        analyzer.dispose()

        verify(scanner).close()
    }

    private fun MlkitImageBarcodeAnalyzer.analyzeOnWorker(frame: CameraFrame, crop: Rect?): Barcode? {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<Barcode?> { analyze(frame, crop) }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun barcodeScanner(barcodes: List<MlkitBarcode> = emptyList()): BarcodeScanner {
        val scanner = mock(BarcodeScanner::class.java)
        doReturn(Tasks.forResult(barcodes)).`when`(scanner).process(anyValue<InputImage>())
        return scanner
    }

    private fun analyzer(
        scanner: BarcodeScanner,
        currentTimeMs: () -> Long = { 0L },
        logError: (String) -> Unit = {},
        fromByteArray: (ByteArray, Int, Int, Int, Int) -> InputImage =
            { _, _, _, _, _ -> mock(InputImage::class.java) },
    ): MlkitImageBarcodeAnalyzer = MlkitImageBarcodeAnalyzer(
        barcodeScanner = scanner,
        currentTimeMs = currentTimeMs,
        logError = logError,
        fromByteArray = fromByteArray,
    ).also { analyzers += it }

    private class MutableClock(var timeMs: Long = 0L) {
        fun read(): Long = timeMs
    }

    private class FakeFrame(
        private val nv21Bytes: ByteArray = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 3 / 2),
    ) : CameraFrame {
        override val width = FRAME_WIDTH
        override val height = FRAME_HEIGHT
        override val rotationDegree = ROTATION_DEGREES
        var accessCalls = 0
            private set
        var receivedCropRect: Rect? = null
            private set

        override fun <T> useNv21(
            cropRect: Rect?,
            block: (ByteArray, Int, Int, Int) -> T,
        ): T {
            accessCalls += 1
            receivedCropRect = cropRect
            val outputWidth = cropRect?.width ?: width
            val outputHeight = cropRect?.height ?: height
            return block(nv21Bytes, outputWidth, outputHeight, rotationDegree)
        }

        override fun close() = Unit
    }

    private companion object {
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L
        const val FRAME_WIDTH = 8
        const val FRAME_HEIGHT = 6
        const val ROTATION_DEGREES = 90
        const val BARCODE_VALUE = "barcode-value"
        const val DISPLAY_VALUE = "Barcode value"
        const val UNKNOWN_FORMAT_CODE = 0

        fun <T> anyValue(): T = any<T>()
    }
}
// endregion

// region MlkitAnalyzerLifetimeTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class MlkitAnalyzerLifetimeTest {
    @Test
    fun `already interrupted worker does not borrow bytes or submit recognition`() {
        val scanner = mock(BarcodeScanner::class.java)
        val frame = BorrowedFrame()
        val analyzer = analyzer(scanner)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> {
                Thread.currentThread().interrupt()
                try {
                    assertNull(analyzer.analyze(frame, null))
                    Thread.currentThread().isInterrupted
                } finally {
                    Thread.interrupted()
                }
            }.get(5, TimeUnit.SECONDS))
            assertFalse(frame.wasAccessed)
            verify(scanner, never()).process(any(InputImage::class.java))
        } finally {
            executor.shutdownNow()
            analyzer.dispose()
        }
    }

    @Test
    fun `interrupt concurrent with an already completed task still suppresses result`() {
        val barcode = mock(MlkitBarcode::class.java)
        doReturn("value").`when`(barcode).rawValue
        val scanner = mock(BarcodeScanner::class.java)
        doAnswer {
            Thread.currentThread().interrupt()
            Tasks.forResult(listOf(barcode))
        }.`when`(scanner).process(any(InputImage::class.java))
        val analyzer = analyzer(scanner)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> {
                try {
                    assertNull(analyzer.analyze(BorrowedFrame(), null))
                    Thread.currentThread().isInterrupted
                } finally {
                    Thread.interrupted()
                }
            }.get(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            analyzer.dispose()
        }
    }

    @Test
    fun `interruption keeps buffer and recognizer alive until pending task completes`() {
        assertPendingTaskLifetime(interruptCount = 1, failTask = false)
    }

    @Test
    fun `repeated interruptions never resubmit recognition or release its buffer early`() {
        assertPendingTaskLifetime(interruptCount = 3, failTask = false)
    }

    @Test
    fun `task failure after interruption releases resources and restores interrupt status`() {
        assertPendingTaskLifetime(interruptCount = 1, failTask = true)
    }

    @Test
    fun `main thread is rejected before borrowing bytes or starting ML Kit`() {
        val scanner = mock(BarcodeScanner::class.java)
        val frame = BorrowedFrame()
        val analyzer = analyzer(scanner)
        try {
            assertTrue(runCatching { analyzer.analyze(frame, null) }.exceptionOrNull() is IllegalStateException)
            assertFalse(frame.wasAccessed)
            verify(scanner, never()).process(any(InputImage::class.java))
        } finally {
            analyzer.dispose()
        }
    }

    private fun assertPendingTaskLifetime(interruptCount: Int, failTask: Boolean) {
        val scanner = mock(BarcodeScanner::class.java)
        val task = TaskCompletionSource<List<MlkitBarcode>>()
        val processing = CountDownLatch(1)
        doAnswer {
            processing.countDown()
            task.task
        }.`when`(scanner).process(any(InputImage::class.java))
        val frame = BorrowedFrame()
        doAnswer {
            assertFalse("SDK closes only after the buffer lease ends", frame.bufferInUse.get())
            null
        }.`when`(scanner).close()
        val errors = mutableListOf<String>()
        val analyzer = analyzer(scanner, errors::add)
        val worker = AtomicReference<Thread>()
        val returned = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                worker.set(Thread.currentThread())
                try {
                    assertNull(analyzer.analyze(frame, null))
                    Thread.currentThread().isInterrupted
                } finally {
                    returned.countDown()
                }
            }
            assertTrue(processing.await(5, TimeUnit.SECONDS))
            analyzer.dispose()

            repeat(interruptCount) {
                worker.get().interrupt()
                assertFalse(
                    "Interrupted await must not release an in-use buffer",
                    returned.await(100, TimeUnit.MILLISECONDS),
                )
                assertTrue(frame.bufferInUse.get())
                verify(scanner, never()).close()
            }
            if (failTask) {
                task.setException(IllegalStateException("Recognition failed"))
            } else {
                val barcode = mock(MlkitBarcode::class.java)
                doReturn("value").`when`(barcode).rawValue
                task.setResult(listOf(barcode))
            }

            assertTrue("Interrupt status is restored after safe completion", result.get(5, TimeUnit.SECONDS))
            assertFalse(frame.bufferInUse.get())
            assertEquals(if (failTask) 1 else 0, errors.size)
            verify(scanner).process(any(InputImage::class.java))
            verify(scanner).close()
        } finally {
            task.trySetResult(emptyList())
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            analyzer.dispose()
        }
    }

    private fun analyzer(
        scanner: BarcodeScanner,
        logError: (String) -> Unit = {},
    ) = MlkitImageBarcodeAnalyzer(
        barcodeScanner = scanner,
        currentTimeMs = { 0L },
        logError = logError,
        fromByteArray = { _, _, _, _, _ -> mock(InputImage::class.java) },
    )

    private class BorrowedFrame : CameraFrame {
        override val width = 2
        override val height = 2
        override val rotationDegree = 0
        val bufferInUse = AtomicBoolean(false)
        var wasAccessed = false

        override fun <T> useNv21(cropRect: Rect?, block: (ByteArray, Int, Int, Int) -> T): T {
            wasAccessed = true
            bufferInUse.set(true)
            return try {
                block(ByteArray(6), width, height, rotationDegree)
            } finally {
                bufferInUse.set(false)
            }
        }

        override fun close() = error("Analyzer borrows but does not own the frame")
    }
}
// endregion
