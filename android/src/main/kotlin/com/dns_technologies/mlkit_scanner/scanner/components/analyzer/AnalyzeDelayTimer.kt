package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Throttles analyzer attempts by keeping a delay window after recognition attempts. */
internal class AnalyzeDelayTimer(
    private val minimumAcceptedPeriodMs: Int,
) {
    private var executor: ScheduledExecutorService? = null

    /** Indicates whether a delay window is currently active. */
    var isRunning = false
        private set

    /** Restarts the delay timer when the requested period is large enough. */
    fun restart(periodMs: Int) {
        stop()
        if (!shouldAcceptPeriod(periodMs)) return

        isRunning = true
        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.schedule({
            isRunning = false
        }, periodMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Stops the active delay timer, if one exists. */
    fun stop() {
        executor?.shutdownNow()
        executor = null
        isRunning = false
    }

    /** Returns true when the requested period should enable delay throttling. */
    private fun shouldAcceptPeriod(periodMs: Int): Boolean = periodMs > minimumAcceptedPeriodMs
}
