package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Throttles analyzer attempts by keeping a delay window after recognition attempts. */
internal class AnalyzeDelayTimer(
    periodMs: Int,
    private val currentTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val periodMs = AtomicInteger(periodMs)

    private val nextAcceptedAnalysisTimeMs = AtomicLong(0L)

    /** Indicates whether a delay window is currently active. */
    val isRunning: Boolean
        get() = currentTimeMs() < nextAcceptedAnalysisTimeMs.get()

    /** Updates delay period used by the next timer restart. */
    fun updatePeriod(periodMs: Int) {
        this.periodMs.set(periodMs)
    }

    /** Restarts the delay timer using the configured period. */
    fun restart() {
        val acceptedPeriodMs = maxOf(periodMs.get(), MIN_ANALYZE_DELAY_MS)
        nextAcceptedAnalysisTimeMs.set(currentTimeMs() + acceptedPeriodMs)
    }

    /** Stops the active delay timer, if one exists. */
    fun stop() {
        nextAcceptedAnalysisTimeMs.set(0L)
    }

    private companion object {
        // Minimum delay between frames at 60 FPS. Used as a lower bound for scan throttling.
        const val MIN_ANALYZE_DELAY_MS = 16
    }
}
