package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job

/** Tracks cooldown for the current scan. Frame checks and completions must be serialized. */
internal class FrameAnalysisGate(
    successfulScanPeriodMs: Int,
    /** Monotonic clock used to compare analysis cooldown deadlines. */
    private val currentTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Cooldown applied after a barcode is found; safe to update during analysis. */
    private val successfulScanPeriodMs = AtomicInteger(successfulScanPeriodMs)
    /** Scan whose previous result controls frame eligibility. */
    private var scan: Job? = null
    /** Earliest monotonic time at which another frame in that scan may be recognized. */
    private var nextAnalysisTimeMs = 0L

    /** A new scan is immediately eligible, regardless of the previous scan's cooldown. */
    fun acceptsFrame(scan: Job? = null): Boolean {
        if (this.scan !== scan) {
            this.scan = scan
            nextAnalysisTimeMs = 0L
        }
        return currentTimeMs() >= nextAnalysisTimeMs
    }

    /** Updates the cooldown read when a successful recognition completes. */
    fun updateSuccessfulScanPeriod(periodMs: Int) {
        successfulScanPeriodMs.set(periodMs)
    }

    /** Starts the next cooldown from completion using the recognition outcome. */
    fun completeAnalysis(barcodeFound: Boolean) {
        nextAnalysisTimeMs =
            currentTimeMs() +
                if (barcodeFound) {
                    successfulScanPeriodMs.get().toLong()
                } else {
                    FAILED_ANALYSIS_INTERVAL_MS
                }
    }

    private companion object {
        /** Interval after a recognition attempt that did not find a barcode. */
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L
    }
}
