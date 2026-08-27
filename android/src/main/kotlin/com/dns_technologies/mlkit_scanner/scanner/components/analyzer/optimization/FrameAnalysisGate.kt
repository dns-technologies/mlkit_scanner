package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Accepts the first frame available after the cooldown selected by the previous result. */
internal class FrameAnalysisGate(
    successfulScanPeriodMs: Int,
    private val currentTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val successfulScanPeriodMs = AtomicInteger(successfulScanPeriodMs)
    private val nextAnalysisTimeMs = AtomicLong()

    init {
        require(successfulScanPeriodMs >= 0)
    }

    /** Returns whether the current frame is the first one available after the active cooldown. */
    fun acceptsFrame(): Boolean = currentTimeMs() >= nextAnalysisTimeMs.get()

    /** Updates the cooldown read when a successful recognition completes. */
    fun updateSuccessfulScanPeriod(periodMs: Int) {
        require(periodMs >= 0)
        successfulScanPeriodMs.set(periodMs)
    }

    /** Starts the next cooldown from completion using the recognition outcome. */
    fun completeAnalysis(barcodeFound: Boolean) {
        nextAnalysisTimeMs.set(
            currentTimeMs() + if (barcodeFound) {
                successfulScanPeriodMs.get().toLong()
            } else {
                FAILED_ANALYSIS_INTERVAL_MS
            },
        )
    }

    private companion object {
        /** Interval after a recognition attempt that did not find a barcode. */
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L
    }
}
