package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization

import android.os.SystemClock
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.LumaGrid
import com.dns_technologies.mlkit_scanner.scanner.components.camera.LumaSamplingFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.Job

/** Tracks cooldown for the current scan. Frame checks and completions must be serialized. */
internal class FrameAnalysisGate(
    successfulScanPeriodMs: Int,
    private val currentTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val successfulScanPeriodMs = AtomicInteger(successfulScanPeriodMs)
    private var scan: Job? = null
    private var nextAnalysisTimeMs = 0L
    private var nextSceneProbeMs = 0L
    private val referenceSamples = ByteArray(LumaGrid.SAMPLE_COUNT)
    private val probeSamples = ByteArray(LumaGrid.SAMPLE_COUNT)
    private val pendingWeakChanges = IntArray(WEAK_CHANGE_COUNT)
    private val currentWeakChanges = IntArray(WEAK_CHANGE_COUNT)
    private var hasPendingWeakChange = false
    private var acceptedEarlyRetry = false
    private var hasReference = false
    private var referenceCrop: Rect? = null
    private var referenceWidth = 0
    private var referenceHeight = 0
    private var referenceRotation = 0

    /** A new scan is immediately eligible, regardless of the previous scan's cooldown. */
    fun acceptsFrame(scan: Job? = null, frame: CameraFrame? = null, cropRect: Rect? = null): Boolean {
        if (this.scan !== scan) {
            this.scan = scan
            nextAnalysisTimeMs = 0L
            clearReference()
        }
        val now = currentTimeMs()
        val lumaFrame = frame as? LumaSamplingFrame
        if (now >= nextAnalysisTimeMs) {
            hasPendingWeakChange = false
            acceptedEarlyRetry = false
            hasReference = lumaFrame?.sampleLuma(cropRect, referenceSamples) == true
            if (hasReference && lumaFrame != null) {
                referenceCrop = cropRect
                referenceWidth = lumaFrame.width
                referenceHeight = lumaFrame.height
                referenceRotation = lumaFrame.rotationDegree
            }
            return true
        }

        val sceneRetry = acceptsSceneRetry(now, lumaFrame, cropRect)
        // One scene retry per miss prevents repeated analysis of a moving background.
        if (sceneRetry) clearReference()
        acceptedEarlyRetry = sceneRetry
        return sceneRetry
    }

    private fun acceptsSceneRetry(now: Long, lumaFrame: LumaSamplingFrame?, cropRect: Rect?): Boolean {
        if (!hasReference || now < nextSceneProbeMs) return false
        if (lumaFrame == null) {
            hasPendingWeakChange = false
            return false
        }
        // Leave a buffer before the scheduled attempt because the early retry uses the same analyzer.
        if (nextAnalysisTimeMs - now < SCENE_RETRY_GUARD_MS) return false
        if (referenceCrop != cropRect || referenceWidth != lumaFrame.width ||
            referenceHeight != lumaFrame.height || referenceRotation != lumaFrame.rotationDegree
        ) {
            clearReference()
            return false
        }
        nextSceneProbeMs = now + SCENE_PROBE_INTERVAL_MS
        if (!lumaFrame.sampleLuma(cropRect, probeSamples)) {
            hasPendingWeakChange = false
            return false
        }
        return sceneChanged()
    }

    /** Updates the cooldown read when a successful recognition completes. */
    fun updateSuccessfulScanPeriod(periodMs: Int) {
        successfulScanPeriodMs.set(periodMs)
    }

    /** An empty early retry leaves the next scheduled attempt due at its original time. */
    fun completeAnalysis(barcodeFound: Boolean) {
        val now = currentTimeMs()
        if (barcodeFound) {
            nextAnalysisTimeMs = now + successfulScanPeriodMs.get().toLong()
            clearReference()
        } else if (!acceptedEarlyRetry) {
            nextAnalysisTimeMs = now + FAILED_ANALYSIS_INTERVAL_MS
            nextSceneProbeMs = now + EARLIEST_SCENE_RETRY_MS
        }
        acceptedEarlyRetry = false
    }

    private fun clearReference() {
        hasReference = false
        referenceCrop = null
        hasPendingWeakChange = false
    }

    private fun sceneChanged(): Boolean {
        var significant = 0
        for (index in referenceSamples.indices) {
            val previous = referenceSamples[index].toInt() and 0xff
            val next = probeSamples[index].toInt() and 0xff
            val delta = next - previous
            if (abs(delta) < MIN_LUMA_CHANGE) continue
            if (significant == WEAK_CHANGE_COUNT) return true
            // One-based indices preserve the direction of the first sample.
            currentWeakChanges[significant++] = if (delta > 0) index + 1 else -(index + 1)
        }
        if (significant != WEAK_CHANGE_COUNT) {
            hasPendingWeakChange = false
            return false
        }
        // Confirm weak changes at the same locations and in the same direction to reject one-off exposure shifts.
        val confirmed = hasPendingWeakChange && currentWeakChanges.contentEquals(pendingWeakChanges)
        currentWeakChanges.copyInto(pendingWeakChanges)
        hasPendingWeakChange = true
        return confirmed
    }

    private companion object {
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L
        const val EARLIEST_SCENE_RETRY_MS = 150L
        const val SCENE_PROBE_INTERVAL_MS = 100L
        const val SCENE_RETRY_GUARD_MS = 100L
        const val MIN_LUMA_CHANGE = 24
        const val MIN_CHANGED_SAMPLES = 4
        const val WEAK_CHANGE_COUNT = MIN_CHANGED_SAMPLES - 1
    }
}
