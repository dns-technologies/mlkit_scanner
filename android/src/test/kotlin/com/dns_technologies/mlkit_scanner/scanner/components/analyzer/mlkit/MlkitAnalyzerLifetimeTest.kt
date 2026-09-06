package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.mlkit

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode as MlkitBarcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
