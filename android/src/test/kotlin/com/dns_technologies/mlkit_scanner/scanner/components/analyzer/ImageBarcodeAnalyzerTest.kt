package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class ImageBarcodeAnalyzerTest {
    @Test
    fun `concurrent frame is skipped while analysis is running`() {
        val analyzer = BlockingAnalyzer()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val createdFrames = AtomicInteger()
            val firstAnalysis = executor.submit<Barcode?> {
                analyzer.analyze {
                    createdFrames.incrementAndGet()
                    image
                }
            }
            assertTrue(analyzer.analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            assertNull(analyzer.analyze {
                createdFrames.incrementAndGet()
                image
            })
            assertEquals(1, analyzer.analysisCalls)
            assertEquals(1, createdFrames.get())

            analyzer.allowAnalysisToFinish.countDown()
            firstAnalysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            analyzer.allowAnalysisToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `dispose waits for running analysis before releasing resources`() {
        val analyzer = BlockingAnalyzer()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val analysis = executor.submit<Barcode?> { analyzer.analyze { image } }
            assertTrue(analyzer.analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            val disposalStarted = CountDownLatch(1)
            val disposal = executor.submit {
                disposalStarted.countDown()
                analyzer.dispose()
            }
            assertTrue(disposalStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertFalse(analyzer.resourcesDisposed.await(SHORT_WAIT_MS, TimeUnit.MILLISECONDS))

            analyzer.allowAnalysisToFinish.countDown()
            analysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            disposal.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            assertTrue(analyzer.resourcesDisposed.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(1, analyzer.disposeCalls)
        } finally {
            analyzer.allowAnalysisToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `analysis is ignored after dispose and resources are released once`() {
        val analyzer = BlockingAnalyzer(blockAnalysis = false)

        analyzer.dispose()
        analyzer.dispose()

        var frameCreated = false
        assertNull(analyzer.analyze {
            frameCreated = true
            image
        })
        assertEquals(0, analyzer.analysisCalls)
        assertFalse(frameCreated)
        assertEquals(1, analyzer.disposeCalls)
    }

    @Test
    fun `frame is not created while analysis delay is active`() {
        val analyzer = BlockingAnalyzer(blockAnalysis = false)
        val createdFrames = AtomicInteger()

        analyzer.analyze {
            createdFrames.incrementAndGet()
            image
        }
        assertNull(analyzer.analyze {
            createdFrames.incrementAndGet()
            image
        })

        assertEquals(1, createdFrames.get())
        assertEquals(1, analyzer.analysisCalls)
    }

    private class BlockingAnalyzer(
        private val blockAnalysis: Boolean = true,
    ) : ImageBarcodeAnalyzer(currentTimeMs = { 0L }) {
        val analysisStarted = CountDownLatch(1)
        val allowAnalysisToFinish = CountDownLatch(if (blockAnalysis) 1 else 0)
        val resourcesDisposed = CountDownLatch(1)

        @Volatile
        var analysisCalls = 0
            private set

        @Volatile
        var disposeCalls = 0
            private set

        override fun analyzeImage(image: AnalysingImage): Barcode? {
            analysisCalls += 1
            analysisStarted.countDown()
            allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return null
        }

        override fun disposeAnalyzer() {
            disposeCalls += 1
            resourcesDisposed.countDown()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 1_000L
        const val SHORT_WAIT_MS = 100L
        val image: AnalysingImage = mock(AnalysingImage::class.java)
    }
}
