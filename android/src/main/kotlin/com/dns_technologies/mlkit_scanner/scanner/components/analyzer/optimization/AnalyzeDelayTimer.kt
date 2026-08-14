package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import android.os.SystemClock

/** Throttles analyzer attempts by keeping a delay window after recognition attempts. */
internal class AnalyzeDelayTimer(
    periodMs: Int,
    private val currentTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var periodMs: Int = periodMs

    private var nextAcceptedAnalysisTimeMs = 0L

    /** Indicates whether a delay window is currently active. */
    val isRunning: Boolean
        get() = currentTimeMs() < nextAcceptedAnalysisTimeMs

    /** Updates delay period used by the next timer restart. */
    fun updatePeriod(periodMs: Int) {
        this.periodMs = periodMs
    }

    /** Restarts the delay timer using the configured period. */
    fun restart() {
        val acceptedPeriodMs = maxOf(periodMs, MIN_ANALYZE_DELAY_MS)
        nextAcceptedAnalysisTimeMs = currentTimeMs() + acceptedPeriodMs
    }

    /** Stops the active delay timer, if one exists. */
    fun stop() {
        nextAcceptedAnalysisTimeMs = 0L
    }

    private companion object {
        // Minimum delay between frames at 60 FPS. Used as a lower bound for scan throttling.
        const val MIN_ANALYZE_DELAY_MS = 16
    }
}
