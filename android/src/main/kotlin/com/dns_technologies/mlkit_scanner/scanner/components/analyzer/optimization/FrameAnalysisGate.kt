package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/**
 * Samples every third available frame and applies the configured cooldown only after recognition.
 */
internal class FrameAnalysisGate(
    successfulScanPeriodMs: Int,
    private val currentTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val state = AtomicReference(State(successfulScanPeriodMs = successfulScanPeriodMs))

    /** Consumes one incoming frame and returns whether it should be analyzed. */
    fun acceptsFrame(): Boolean {
        val currentTimeMs = currentTimeMs()
        while (true) {
            val current = state.get()
            if (currentTimeMs < current.nextAnalysisTimeMs) return false
            if (current.framesToSkip == 0) return true
            if (state.compareAndSet(current, current.copy(framesToSkip = current.framesToSkip - 1))) {
                return false
            }
        }
    }

    /** Updates the cooldown applied after the next successful recognition. */
    fun updateSuccessfulScanPeriod(periodMs: Int) {
        updateState { current -> current.copy(successfulScanPeriodMs = periodMs) }
    }

    /** Records a completed analysis and chooses frame sampling or successful-result cooldown. */
    fun completeAnalysis(barcodeFound: Boolean) {
        val completionTimeMs = currentTimeMs()
        updateState { current ->
            if (barcodeFound) {
                current.copy(
                    framesToSkip = 0,
                    nextAnalysisTimeMs = completionTimeMs + current.successfulScanPeriodMs,
                )
            } else {
                current.copy(
                    framesToSkip = FRAMES_TO_SKIP,
                    nextAnalysisTimeMs = 0L,
                )
            }
        }
    }

    /** Clears successful-result cooldown and pending frame skips. */
    fun reset() {
        updateState { current ->
            current.copy(
                framesToSkip = 0,
                nextAnalysisTimeMs = 0L,
            )
        }
    }

    private inline fun updateState(transform: (State) -> State) {
        while (true) {
            val current = state.get()
            if (state.compareAndSet(current, transform(current))) return
        }
    }

    private data class State(
        val successfulScanPeriodMs: Int,
        val framesToSkip: Int = 0,
        val nextAnalysisTimeMs: Long = 0L,
    )

    private companion object {
        /** At 30 FPS this caps failed recognition attempts at roughly ten per second. */
        const val FRAMES_TO_SKIP = 2
    }
}
