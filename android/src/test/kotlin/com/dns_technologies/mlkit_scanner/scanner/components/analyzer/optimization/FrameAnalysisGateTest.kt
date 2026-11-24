package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import com.dns_technologies.mlkit_scanner.scanner.components.camera.LumaGrid
import com.dns_technologies.mlkit_scanner.scanner.components.camera.LumaSamplingFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FrameAnalysisGateTest {
    @Test
    fun `period update does not retroactively change an active cooldown`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(100, clock::read)
        gate.completeAnalysis(barcodeFound = true)
        gate.updateSuccessfulScanPeriod(0)

        clock.timeMs = 99
        assertFalse(gate.acceptsFrame())
        clock.timeMs = 100
        assertTrue(gate.acceptsFrame())
        gate.completeAnalysis(barcodeFound = true)
        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `maximum integer period uses long arithmetic for its deadline`() {
        val clock = MutableClock(COMPLETION_TIME_MS)
        val gate = FrameAnalysisGate(Int.MAX_VALUE, clock::read)
        gate.completeAnalysis(barcodeFound = true)

        clock.timeMs = COMPLETION_TIME_MS + Int.MAX_VALUE.toLong() - 1
        assertFalse(gate.acceptsFrame())
        clock.timeMs++
        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `checking skipped frames does not postpone the deadline`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(0, clock::read)
        gate.completeAnalysis(barcodeFound = false)
        repeat(1000) {
            clock.timeMs = it.toLong()
            assertFalse(gate.acceptsFrame())
        }
        clock.timeMs = FAILED_ANALYSIS_INTERVAL_MS
        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `first frame is accepted immediately`() {
        val gate = FrameAnalysisGate(successfulScanPeriodMs = 0) { 0L }

        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `failed recognition waits one second from completion`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(successfulScanPeriodMs = 5_000, clock::read)
        clock.timeMs = COMPLETION_TIME_MS
        gate.completeAnalysis(barcodeFound = false)

        clock.timeMs = COMPLETION_TIME_MS + FAILED_ANALYSIS_INTERVAL_MS - 1
        assertFalse(gate.acceptsFrame())
        clock.timeMs += 1
        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `successful recognition waits exact configured period from completion`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(successfulScanPeriodMs = MIN_SUCCESS_PERIOD_MS, clock::read)
        clock.timeMs = COMPLETION_TIME_MS
        gate.completeAnalysis(barcodeFound = true)

        clock.timeMs = COMPLETION_TIME_MS + MIN_SUCCESS_PERIOD_MS - 1
        assertFalse(gate.acceptsFrame())
        clock.timeMs += 1
        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `period updated during analysis is used at successful completion`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(successfulScanPeriodMs = 100, clock::read)
        gate.updateSuccessfulScanPeriod(250)
        gate.completeAnalysis(barcodeFound = true)

        clock.timeMs = 249
        assertFalse(gate.acceptsFrame())
        clock.timeMs = 250
        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `zero successful period accepts next available frame`() {
        val gate = FrameAnalysisGate(successfulScanPeriodMs = 0) { 0L }

        gate.completeAnalysis(barcodeFound = true)

        assertTrue(gate.acceptsFrame())
    }

    @Test
    fun `three matching changes allow one early retry and keep the scheduled deadline`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(0, clock::read)
        val frame = SampledFrame()
        assertTrue(gate.acceptsFrame(frame = frame))
        gate.completeAnalysis(barcodeFound = false)

        frame.setChangedSamples(64, 0, 8, 16)
        clock.timeMs = 150
        assertFalse(gate.acceptsFrame(frame = frame))
        clock.timeMs = 249
        assertFalse(gate.acceptsFrame(frame = frame))
        clock.timeMs = 250
        assertTrue(gate.acceptsFrame(frame = frame))
        gate.completeAnalysis(barcodeFound = false)

        clock.timeMs = 999
        assertFalse(gate.acceptsFrame(frame = frame))
        clock.timeMs = 1000
        assertTrue(gate.acceptsFrame(frame = frame))
    }

    @Test
    fun `three changes must match both positions and directions`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(0, clock::read)
        val frame = SampledFrame()
        assertTrue(gate.acceptsFrame(frame = frame))
        gate.completeAnalysis(barcodeFound = false)

        frame.setChangedSamples(64, 0, 8, 16)
        clock.timeMs = 150
        assertFalse(gate.acceptsFrame(frame = frame))
        frame.setChangedSamples(192, 0, 8, 16)
        clock.timeMs = 250
        assertFalse(gate.acceptsFrame(frame = frame))
        frame.setChangedSamples(192, 0, 8, 17)
        clock.timeMs = 350
        assertFalse(gate.acceptsFrame(frame = frame))
        clock.timeMs = 450
        assertTrue(gate.acceptsFrame(frame = frame))
    }

    @Test
    fun `a probe without three changes clears their previous observation`() {
        val clock = MutableClock()
        val gate = FrameAnalysisGate(0, clock::read)
        val frame = SampledFrame()
        assertTrue(gate.acceptsFrame(frame = frame))
        gate.completeAnalysis(barcodeFound = false)

        frame.setChangedSamples(64, 0, 8, 16)
        clock.timeMs = 150
        assertFalse(gate.acceptsFrame(frame = frame))
        frame.setChangedSamples(64, 0, 8)
        clock.timeMs = 250
        assertFalse(gate.acceptsFrame(frame = frame))
        frame.setChangedSamples(64, 0, 8, 16)
        clock.timeMs = 350
        assertFalse(gate.acceptsFrame(frame = frame))
        clock.timeMs = 450
        assertTrue(gate.acceptsFrame(frame = frame))
    }

    private class SampledFrame : LumaSamplingFrame {
        override val width = 128
        override val height = 128
        override val rotationDegree = 0
        private val samples = ByteArray(LumaGrid.SAMPLE_COUNT) { 128.toByte() }

        fun setChangedSamples(value: Int, vararg indices: Int) {
            samples.fill(128.toByte())
            for (index in indices) samples[index] = value.toByte()
        }

        override fun sampleLuma(cropRect: Rect?, destination: ByteArray): Boolean {
            samples.copyInto(destination)
            return true
        }

        override fun <T> useNv21(cropRect: Rect?, block: (ByteArray, Int, Int, Int) -> T): T =
            error("The gate only samples luma")

        override fun close() = Unit
    }

    private class MutableClock(var timeMs: Long = 0L) {
        fun read(): Long = timeMs
    }

    private companion object {
        const val COMPLETION_TIME_MS = 200L
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L
        const val MIN_SUCCESS_PERIOD_MS = 16
    }
}
