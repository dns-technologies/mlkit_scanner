package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/** Limits barcode recognition to three attempts before applying the configured cooldown. */
internal class AnalysisAttemptWindow(
    periodMs: Int,
    private val currentTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val state = AtomicReference(State(periodMs = periodMs))

    /** Indicates whether the current attempt window can accept another analysis. */
    val acceptsAttempt: Boolean
        get() = currentTimeMs() >= state.get().nextWindowTimeMs

    /** Updates the cooldown used after the current attempt window finishes. */
    fun updatePeriod(periodMs: Int) {
        updateState { current -> current.copy(periodMs = periodMs) }
    }

    /** Records one completed attempt and closes the window after a hit or its third attempt. */
    fun completeAttempt(barcodeFound: Boolean) {
        val completionTimeMs = currentTimeMs()
        updateState { current ->
            if (barcodeFound || current.attemptsRemaining == 1) {
                current.copy(
                    attemptsRemaining = ATTEMPTS_PER_WINDOW,
                    nextWindowTimeMs = completionTimeMs +
                        maxOf(current.periodMs, MIN_WINDOW_DELAY_MS),
                )
            } else {
                current.copy(attemptsRemaining = current.attemptsRemaining - 1)
            }
        }
    }

    /** Clears the cooldown and restores a full attempt window. */
    fun reset() {
        updateState { current ->
            current.copy(
                attemptsRemaining = ATTEMPTS_PER_WINDOW,
                nextWindowTimeMs = 0L,
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
        val periodMs: Int,
        val attemptsRemaining: Int = ATTEMPTS_PER_WINDOW,
        val nextWindowTimeMs: Long = 0L,
    )

    private companion object {
        const val ATTEMPTS_PER_WINDOW = 3

        // Lower bound prevents an exhausted window from reopening on the same 60 FPS frame tick.
        const val MIN_WINDOW_DELAY_MS = 16
    }
}
