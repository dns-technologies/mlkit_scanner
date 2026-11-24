package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.LumaSamplingFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class ImageBarcodeAnalyzerTest {
    @Test
    fun `dispose from analysis defers cleanup until that invocation returns`() {
        lateinit var analyzer: TestAnalyzer
        analyzer = TestAnalyzer {
            analyzer.dispose()
            assertEquals(0, analyzer.disposeCalls.get())
            assertNull(analyzer.analyze(TEST_FRAME, null))
            TEST_BARCODE
        }

        assertSame(TEST_BARCODE, analyzer.analyze(TEST_FRAME, null))
        assertEquals(1, analyzer.disposeCalls.get())
        analyzer.dispose()
        assertEquals(1, analyzer.disposeCalls.get())
    }

    @Test
    fun `cleanup may reenter disposal without running twice`() {
        lateinit var analyzer: TestAnalyzer
        analyzer = TestAnalyzer(onDispose = { analyzer.dispose() })
        analyzer.dispose()

        assertEquals(1, analyzer.disposeCalls.get())
    }

    @Test
    fun `throwing cleanup leaves analyzer permanently disposed`() {
        val failure = IllegalStateException("Close failed")
        val analyzer = TestAnalyzer(onDispose = { throw failure })

        assertSame(failure, runCatching { analyzer.dispose() }.exceptionOrNull())
        analyzer.dispose()
        assertNull(analyzer.analyze(TEST_FRAME, null))
        assertEquals(1, analyzer.disposeCalls.get())
    }

    @Test
    fun `disposal racing with analysis completion closes resources exactly once`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val barrier = CyclicBarrier(2)
                val analyzer = TestAnalyzer {
                    barrier.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    TEST_BARCODE
                }
                val analysis = executor.submit<Barcode?> { analyzer.analyze(TEST_FRAME, null) }
                val disposal = executor.submit {
                    barrier.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    analyzer.dispose()
                }

                analysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                disposal.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                assertEquals(1, analyzer.disposeCalls.get())
                assertNull(analyzer.analyze(TEST_FRAME, null))
                assertEquals(1, analyzer.analysisCalls.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `slow cleanup does not hold lifecycle lock or block a repeated dispose`() {
        val cleanupStarted = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val analyzer = TestAnalyzer(onDispose = {
            cleanupStarted.countDown()
            assertTrue(finishCleanup.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val disposal = executor.submit { analyzer.dispose() }
            assertTrue(cleanupStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val reentry = executor.submit {
                analyzer.dispose()
                assertNull(analyzer.analyze(TEST_FRAME, null))
            }
            reentry.get(SHORT_WAIT_MS, TimeUnit.MILLISECONDS)
            finishCleanup.countDown()
            disposal.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertEquals(1, analyzer.disposeCalls.get())
        } finally {
            finishCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `clock failure during admission releases execution ownership`() {
        var reads = 0
        val failure = IllegalStateException("Clock failed")
        val analyzer = TestAnalyzer(currentTimeMs = {
            if (reads++ == 0) throw failure
            0L
        }) { TEST_BARCODE }

        assertSame(failure, runCatching { analyzer.analyze(TEST_FRAME, null) }.exceptionOrNull())
        assertSame(TEST_BARCODE, analyzer.analyze(TEST_FRAME, null))
        assertEquals(1, analyzer.analysisCalls.get())
        analyzer.dispose()
        assertEquals(1, analyzer.disposeCalls.get())
    }

    @Test
    fun `clock failure at completion does not strand analyzer ownership`() {
        var reads = 0
        val failure = IllegalStateException("Clock failed")
        val analyzer = TestAnalyzer(currentTimeMs = {
            if (reads++ == 1) throw failure
            0L
        }) {
            TEST_BARCODE
        }

        assertSame(failure, runCatching { analyzer.analyze(TEST_FRAME, null) }.exceptionOrNull())
        analyzer.dispose()
        assertEquals(1, analyzer.disposeCalls.get())
        assertNull(analyzer.analyze(TEST_FRAME, null))
    }

    @Test
    fun `analyzer forwards borrowed frame and crop without closing or reading them itself`() {
        val crop = Rect(1, 2, 3, 4)
        val borrowedFrame = object : CameraFrame {
            override val width = 4
            override val height = 4
            override val rotationDegree = 0
            override fun <T> useNv21(cropRect: Rect?, block: (ByteArray, Int, Int, Int) -> T): T =
                error("Base analyzer must not access frame bytes")
            override fun close() = error("Base analyzer must not close a borrowed frame")
        }
        val analyzer = object : ImageBarcodeAnalyzer({ 0L }) {
            override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode {
                assertSame(borrowedFrame, frame)
                assertSame(crop, cropRect)
                return TEST_BARCODE
            }
            override fun disposeAnalyzer() = Unit
        }

        assertSame(TEST_BARCODE, analyzer.analyze(borrowedFrame, crop))
        analyzer.dispose()
    }

    @Test
    fun `concurrent frame is skipped while analysis is running`() {
        val analysisStarted = CountDownLatch(1)
        val allowAnalysisToFinish = CountDownLatch(1)
        val analyzer = TestAnalyzer(
            analysis = {
                analysisStarted.countDown()
                allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                null
            },
        )
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
        val analyzer = TestAnalyzer(
            analysis = {
                analysisStarted.countDown()
                allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                null
            },
        )
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
        val clock = MutableClock()
        val analyzer = TestAnalyzer(
            clock = clock,
            analysis = {
                analysisStarted.countDown()
                allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                TEST_BARCODE
            },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            analyzer.updatePeriod(100)
            val analysis = executor.submit<Barcode?> { analyzer.analyze(TEST_FRAME, null) }
            assertTrue(analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            val periodUpdate = executor.submit { analyzer.updatePeriod(250) }
            periodUpdate.get(SHORT_WAIT_MS, TimeUnit.MILLISECONDS)

            allowAnalysisToFinish.countDown()
            analysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            clock.timeMs = 249
            assertNull(analyzer.analyze(TEST_FRAME, null))
            clock.timeMs = 250
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
    fun `configured period is applied only after recognized barcode`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock = clock) { TEST_BARCODE }
        analyzer.updatePeriod(100)

        analyzer.analyze(TEST_FRAME, null)
        repeat(5) { assertNull(analyzer.analyze(TEST_FRAME, null)) }
        clock.timeMs = 99
        assertNull(analyzer.analyze(TEST_FRAME, null))
        clock.timeMs = 100
        analyzer.analyze(TEST_FRAME, null)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `zero successful recognition period accepts the next frame`() {
        val analyzer = TestAnalyzer(analysis = { TEST_BARCODE })

        repeat(2) { analyzer.analyze(TEST_FRAME, null) }

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `configured period does not throttle failed recognition`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock = clock)
        analyzer.updatePeriod(10_000)

        analyzer.analyze(TEST_FRAME, null)
        repeat(5) { assertNull(analyzer.analyze(TEST_FRAME, null)) }
        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS - 1
        analyzer.analyze(TEST_FRAME, null)
        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS
        analyzer.analyze(TEST_FRAME, null)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `failed analysis consumes an attempt and releases execution lock`() {
        val clock = MutableClock()
        val blockCalls = AtomicInteger()
        val analyzer = TestAnalyzer(
            clock = clock,
            analysis = {
                if (blockCalls.incrementAndGet() == 1) error("analysis failed")
                null
            },
        )

        val error = runCatching { analyzer.analyze(TEST_FRAME, null) }.exceptionOrNull()
        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS - 1
        analyzer.analyze(TEST_FRAME, null)
        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS
        analyzer.analyze(TEST_FRAME, null)

        assertTrue(error is IllegalStateException)
        assertEquals(2, analyzer.analysisCalls.get())
    }


    @Test
    fun `scene change can retry only after the earliest probe time`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock)
        val frame = SceneFrame()

        analyzer.analyze(frame, SCENE_ROI)
        frame.changedCells = 4
        clock.timeMs = 149
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(1, analyzer.analysisCalls.get())

        clock.timeMs = 150
        analyzer.analyze(frame, SCENE_ROI)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `static or small changes do not delay normal fallback and probes are bounded`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock)
        val frame = SceneFrame()

        analyzer.analyze(frame, SCENE_ROI)
        frame.changedCells = 2
        for (time in listOf(150L, 151L, 249L)) {
            clock.timeMs = time
            analyzer.analyze(frame, SCENE_ROI)
        }
        assertEquals(1, analyzer.analysisCalls.get())
        assertEquals(2, frame.samples)

        clock.timeMs = 250
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(3, frame.samples)
        clock.timeMs = 1_000
        analyzer.analyze(frame, SCENE_ROI)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `empty early retry keeps the fixed deadline and spends one retry`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock)
        val frame = SceneFrame()

        analyzer.analyze(frame, SCENE_ROI)
        frame.changedCells = 4
        clock.timeMs = 500
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(2, analyzer.analysisCalls.get())

        frame.changedCells = 8
        clock.timeMs = 750
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(2, analyzer.analysisCalls.get())

        clock.timeMs = 1_000
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(3, analyzer.analysisCalls.get())

        frame.changedCells = 12
        clock.timeMs = 1_150
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(4, analyzer.analysisCalls.get())
    }

    @Test
    fun `scene probe leaves time for upcoming scheduled analysis`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock)
        val frame = SceneFrame()

        analyzer.analyze(frame, SCENE_ROI)
        frame.changedCells = 4
        clock.timeMs = 901
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(1, analyzer.analysisCalls.get())
        assertEquals(1, frame.samples)

        clock.timeMs = 1_000
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `successful early retry starts the configured success cooldown`() {
        val clock = MutableClock()
        var barcode = false
        val analyzer = TestAnalyzer(clock) { if (barcode) TEST_BARCODE else null }
        val frame = SceneFrame()
        analyzer.updatePeriod(2_000)

        analyzer.analyze(frame, SCENE_ROI)
        barcode = true
        frame.changedCells = 4
        clock.timeMs = 200
        assertEquals(TEST_BARCODE, analyzer.analyze(frame, SCENE_ROI))

        clock.timeMs = 1_000
        analyzer.analyze(frame, SCENE_ROI)
        clock.timeMs = 2_199
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(2, analyzer.analysisCalls.get())

        clock.timeMs = 2_200
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(3, analyzer.analysisCalls.get())
    }

    @Test
    fun `unsupported sample keeps the fallback interval`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock)
        val frame = SceneFrame(supported = false)

        analyzer.analyze(frame, SCENE_ROI)
        frame.changedCells = 20
        clock.timeMs = 150
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(1, analyzer.analysisCalls.get())
        assertEquals(1, frame.samples)

        clock.timeMs = 1_000
        analyzer.analyze(frame, SCENE_ROI)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `successful result disables scene probes for its full period`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock) { TEST_BARCODE }
        val frame = SceneFrame()
        analyzer.updatePeriod(2_000)

        analyzer.analyze(frame, SCENE_ROI)
        frame.changedCells = 20
        clock.timeMs = 1_999
        analyzer.analyze(frame, SCENE_ROI)
        assertEquals(1, analyzer.analysisCalls.get())
        assertEquals(1, frame.samples)

        clock.timeMs = 2_000
        analyzer.analyze(frame, SCENE_ROI)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `a new scan discards the previous scene and ignores the cancelled job`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock)
        val oldScan = Job()
        val newScan = Job()
        val frame = SceneFrame()
        analyzer.analyze(frame, SCENE_ROI, oldScan)
        oldScan.cancel()
        frame.changedCells = 20
        clock.timeMs = 50

        analyzer.analyze(frame, SCENE_ROI, oldScan)
        assertEquals(1, analyzer.analysisCalls.get())
        analyzer.analyze(frame, SCENE_ROI, newScan)
        assertEquals(2, analyzer.analysisCalls.get())
        clock.timeMs = 200
        analyzer.analyze(frame, SCENE_ROI, newScan)

        assertEquals(2, analyzer.analysisCalls.get())
    }

    @Test
    fun `cancelled early retry leaves the replacement scan with its normal cooldown`() {
        val clock = MutableClock()
        val previous = Job()
        val replacement = Job()
        val analyzer = TestAnalyzer(clock) {
            if (clock.timeMs == 200L) previous.cancel()
            null
        }
        val frame = SceneFrame()

        analyzer.analyze(frame, SCENE_ROI, previous)
        frame.changedCells = 4
        clock.timeMs = 200
        analyzer.analyze(frame, SCENE_ROI, previous)
        assertEquals(2, analyzer.analysisCalls.get())

        clock.timeMs = 201
        analyzer.analyze(frame, SCENE_ROI, replacement)
        clock.timeMs = 202
        analyzer.analyze(frame, SCENE_ROI, replacement)

        assertEquals(3, analyzer.analysisCalls.get())
    }

    @Test
    fun `different ROI cannot use an old scene sample`() {
        val clock = MutableClock()
        val analyzer = TestAnalyzer(clock)
        val frame = SceneFrame()
        analyzer.analyze(frame, SCENE_ROI)
        frame.changedCells = 20

        clock.timeMs = 150
        analyzer.analyze(frame, Rect(10, 10, 90, 90))

        assertEquals(1, analyzer.analysisCalls.get())
        assertEquals(1, frame.samples)
    }

    private class SceneFrame(private val supported: Boolean = true) : LumaSamplingFrame {
        override val width = 100
        override val height = 100
        override val rotationDegree = 0
        var changedCells = 0
        var samples = 0

        override fun sampleLuma(cropRect: Rect?, destination: ByteArray): Boolean {
            samples++
            if (!supported) return false
            destination.fill(100)
            destination.fill(70, 0, changedCells)
            return true
        }

        override fun <T> useNv21(
            cropRect: Rect?,
            block: (ByteArray, Int, Int, Int) -> T,
        ): T = error("Probe or test analyzer requested a full conversion")

        override fun close() = Unit
    }

    private class TestAnalyzer(
        clock: MutableClock = MutableClock(),
        currentTimeMs: () -> Long = clock::read,
        private val onDispose: () -> Unit = {},
        private val analysis: () -> Barcode? = { null },
    ) : ImageBarcodeAnalyzer(currentTimeMs = currentTimeMs) {
        val analysisCalls = AtomicInteger()
        val disposeCalls = AtomicInteger()
        val resourcesDisposed = CountDownLatch(1)

        override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode? {
            analysisCalls.incrementAndGet()
            return analysis()
        }

        override fun disposeAnalyzer() {
            disposeCalls.incrementAndGet()
            onDispose()
            resourcesDisposed.countDown()
        }
    }

    private class MutableClock(var timeMs: Long = 0L) {
        fun read(): Long = timeMs
    }

    private companion object {
        val SCENE_ROI = Rect(0, 0, 80, 80)
        const val TEST_TIMEOUT_MS = 1_000L
        const val SHORT_WAIT_MS = 100L
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L

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
