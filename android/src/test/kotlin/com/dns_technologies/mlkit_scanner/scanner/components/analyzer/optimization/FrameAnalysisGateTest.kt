package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FrameAnalysisGateTest {
    @Test
    fun `negative periods are rejected without changing existing configuration`() {
        assertTrue(runCatching { FrameAnalysisGate(-1) }.exceptionOrNull() is IllegalArgumentException)
        val clock = MutableClock()
        val gate = FrameAnalysisGate(100, clock::read)
        assertTrue(runCatching { gate.updateSuccessfulScanPeriod(-1) }.exceptionOrNull() is IllegalArgumentException)
        gate.completeAnalysis(barcodeFound = true)

        clock.timeMs = 99
        assertFalse(gate.acceptsFrame())
        clock.timeMs = 100
        assertTrue(gate.acceptsFrame())
    }

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

    private class MutableClock(var timeMs: Long = 0L) {
        fun read(): Long = timeMs
    }

    private companion object {
        const val COMPLETION_TIME_MS = 200L
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L
        const val MIN_SUCCESS_PERIOD_MS = 16
    }
}
