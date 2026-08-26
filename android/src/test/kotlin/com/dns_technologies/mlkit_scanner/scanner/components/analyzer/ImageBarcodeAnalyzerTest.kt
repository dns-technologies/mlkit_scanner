package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class ImageBarcodeAnalyzerTest {
    @Test
    fun `concurrent frame is skipped while analysis is running`() {
        val analysisStarted = CountDownLatch(1)
        val allowAnalysisToFinish = CountDownLatch(1)
        val analyzer = TestAnalyzer {
            analysisStarted.countDown()
            allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            null
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstAnalysis = executor.submit<Barcode?> { analyzer.analyze(TEST_FRAME, null) }
            assertTrue(analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            assertNull(analyzer.analyze(TEST_FRAME, null))
            assertEquals(1, analyzer.analysisCalls.get())

            allowAnalysisToFinish.countDown()
            firstAnalysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            allowAnalysisToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `dispose returns while analysis runs and releases resources after completion`() {
        val analysisStarted = CountDownLatch(1)
        val allowAnalysisToFinish = CountDownLatch(1)
        val analyzer = TestAnalyzer {
            analysisStarted.countDown()
            allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            null
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val analysis = executor.submit<Barcode?> { analyzer.analyze(TEST_FRAME, null) }
            assertTrue(analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            val disposalStarted = CountDownLatch(1)
            val disposal = executor.submit {
                disposalStarted.countDown()
                analyzer.dispose()
            }
            assertTrue(disposalStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            disposal.get(SHORT_WAIT_MS, TimeUnit.MILLISECONDS)
            assertFalse(analyzer.resourcesDisposed.await(SHORT_WAIT_MS, TimeUnit.MILLISECONDS))

            allowAnalysisToFinish.countDown()
            analysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            assertTrue(analyzer.resourcesDisposed.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(1, analyzer.disposeCalls.get())
        } finally {
            allowAnalysisToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `period update returns while analysis is running`() {
        val analysisStarted = CountDownLatch(1)
        val allowAnalysisToFinish = CountDownLatch(1)
        var currentTimeMs = 0L
        val analyzer = TestAnalyzer(currentTimeMs = { currentTimeMs }) {
            analysisStarted.countDown()
            allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            TEST_BARCODE
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            analyzer.updatePeriod(100)
            val analysis = executor.submit<Barcode?> { analyzer.analyze(TEST_FRAME, null) }
            assertTrue(analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            val periodUpdate = executor.submit { analyzer.updatePeriod(250) }
            periodUpdate.get(SHORT_WAIT_MS, TimeUnit.MILLISECONDS)

            allowAnalysisToFinish.countDown()
            analysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            currentTimeMs = 249
            assertNull(analyzer.analyze(TEST_FRAME, null))
            currentTimeMs = 250
            analyzer.analyze(TEST_FRAME, null)
            assertEquals(2, analyzer.analysisCalls.get())
        } finally {
            allowAnalysisToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `analysis is ignored after dispose and resources are released once`() {
        val analyzer = TestAnalyzer()

        analyzer.dispose()
        analyzer.dispose()

        assertNull(analyzer.analyze(TEST_FRAME, null))
        assertEquals(0, analyzer.analysisCalls.get())
        assertEquals(1, analyzer.disposeCalls.get())
    }

    @Test
    fun `failed recognition analyzes every third incoming frame`() {
        val analyzer = TestAnalyzer()

        repeat(7) { analyzer.analyze(TEST_FRAME, null) }

        assertEquals(3, analyzer.analysisCalls.get())
    }

    @Test
    fun `configured period is applied only after recognized barcode`() {
        var currentTimeMs = 0L
        val analyzer = TestAnalyzer(currentTimeMs = { currentTimeMs }) { TEST_BARCODE }
        analyzer.updatePeriod(100)

        analyzer.analyze(TEST_FRAME, null)
        repeat(5) { assertNull(analyzer.analyze(TEST_FRAME, null)) }
        currentTimeMs = 99
        assertNull(analyzer.analyze(TEST_FRAME, null))
        currentTimeMs = 100
        analyzer.analyze(TEST_FRAME, null)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `zero successful recognition period accepts the next frame`() {
        val analyzer = TestAnalyzer { TEST_BARCODE }

        repeat(2) { analyzer.analyze(TEST_FRAME, null) }

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `configured period does not throttle failed recognition`() {
        val analyzer = TestAnalyzer()
        analyzer.updatePeriod(10_000)

        repeat(4) { analyzer.analyze(TEST_FRAME, null) }

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `failed analysis consumes an attempt and releases execution lock`() {
        val blockCalls = AtomicInteger()
        val analyzer = TestAnalyzer {
            if (blockCalls.incrementAndGet() == 1) error("analysis failed")
            null
        }

        val error = runCatching { analyzer.analyze(TEST_FRAME, null) }.exceptionOrNull()
        repeat(2) { analyzer.analyze(TEST_FRAME, null) }
        analyzer.analyze(TEST_FRAME, null)

        assertTrue(error is IllegalStateException)
        assertEquals(2, analyzer.analysisCalls.get())
    }

    private class TestAnalyzer(
        currentTimeMs: () -> Long = { 0L },
        private val analysis: () -> Barcode? = { null },
    ) : ImageBarcodeAnalyzer(currentTimeMs) {
        val analysisCalls = AtomicInteger()
        val disposeCalls = AtomicInteger()
        val resourcesDisposed = CountDownLatch(1)

        override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode? {
            analysisCalls.incrementAndGet()
            return analysis()
        }

        override fun disposeAnalyzer() {
            disposeCalls.incrementAndGet()
            resourcesDisposed.countDown()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 1_000L
        const val SHORT_WAIT_MS = 100L

        val TEST_BARCODE = Barcode(
            rawValue = "barcode",
            displayValue = "barcode",
            format = 1,
            valueType = 1,
        )

        val TEST_FRAME = object : CameraFrame {
            override val width = 2
            override val height = 2
            override val rotationDegree = 0

            override fun <T> useNv21(
                cropRect: Rect?,
                block: (ByteArray, Int, Int, Int) -> T,
            ): T = block(ByteArray(6), width, height, rotationDegree)

            override fun close() = Unit
        }
    }
}
