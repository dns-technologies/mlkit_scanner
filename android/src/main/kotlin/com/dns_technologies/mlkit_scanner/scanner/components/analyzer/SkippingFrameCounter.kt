package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

/** Tracks skipped frames between analyzer attempts. */
internal class SkippingFrameCounter(
    private val skippedFrameCount: Int,
) {
    private var count = 0

    /** Returns true when the current frame should be eligible for analysis. */
    fun shouldAnalyzeCurrentFrame(): Boolean = count % skippedFrameCount == 0

    /** Advances the skipped-frame counter within its configured range. */
    fun advance() {
        count = ++count % skippedFrameCount
    }

    /** Resets the skipped-frame counter after successful recognition. */
    fun reset() {
        count = 0
    }
}
