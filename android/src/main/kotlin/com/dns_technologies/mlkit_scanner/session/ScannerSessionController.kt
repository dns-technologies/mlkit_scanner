package com.dns_technologies.mlkit_scanner.session

import android.content.Context
import android.os.Handler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.x.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.utils.optionalBoolean
import com.dns_technologies.mlkit_scanner.utils.optionalFiniteDouble
import com.dns_technologies.mlkit_scanner.utils.optionalMap
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap

/** Delivers scanner results outside the scanner controller. */
internal fun interface ScanResultSink {
    fun emit(viewId: Int, result: Barcode)
}

/** Main-thread owner of the scanner session, its platform views, and host lifecycle attachment. */
internal class ScannerSessionController(
    private val mainHandler: Handler,
    private val scanResultSink: ScanResultSink,
) {
    var session: ScannerSession? = null
        private set

    private var hostLifecycle: Lifecycle? = null
    private var hostResumed = false
    private val hostLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> updateHostResumed(true)
            Lifecycle.Event.ON_PAUSE -> updateHostResumed(false)
            Lifecycle.Event.ON_DESTROY -> detachHostLifecycle()
            else -> Unit
        }
    }

    /** Creates a platform view in the current scanner session, creating the session if necessary. */
    fun createView(
        context: Context,
        platformViewId: Int,
        creationParams: Any?,
    ): ScannerView {
        val arguments = creationParams.requireMap()
        val viewId = arguments.requireInt(PluginConstants.viewIdArgument)
        val initialZoomRatio = arguments.optionalFiniteDouble(
            PluginConstants.initialZoomRatioArgument,
        )
        val initialZoomRatioFloat = initialZoomRatio?.toFloat()
        if (
            viewId < 0 ||
            viewId != platformViewId ||
            initialZoomRatioFloat != null &&
            (!initialZoomRatioFloat.isFinite() || initialZoomRatioFloat <= 0.0F)
        ) {
            throw PluginError.InvalidArguments
        }

        val initialCropRect = arguments
            .optionalMap(PluginConstants.initialCropRectArgument)
            ?.let(RecognizeVisorCropRect::fromMap)
        val initialFlashEnabled = arguments.optionalBoolean(
            PluginConstants.initialFlashEnabledArgument,
        )

        val activeSession = session ?: createSession(context)
        return activeSession.createView(
            context = context,
            viewId = viewId,
            initialZoomRatio = initialZoomRatio,
            initialCropRect = initialCropRect,
            initialFlashEnabled = initialFlashEnabled,
        )
    }

    /** Replaces the Activity lifecycle observed by this controller. */
    fun attachHostLifecycle(lifecycle: Lifecycle) {
        if (hostLifecycle === lifecycle) {
            syncHostState(lifecycle)
            return
        }
        hostLifecycle?.removeObserver(hostLifecycleObserver)
        hostLifecycle = lifecycle
        lifecycle.addObserver(hostLifecycleObserver)
        syncHostState(lifecycle)
    }

    /** Stops observing the Activity lifecycle and pauses the retained scanner session. */
    fun detachHostLifecycle() {
        hostLifecycle?.removeObserver(hostLifecycleObserver)
        hostLifecycle = null
        updateHostResumed(false)
    }

    /** Releases and forgets the current scanner session. */
    fun release() {
        val activeSession = session
        session = null
        activeSession?.release()
    }

    /** Creates and owns the concrete Android scanner pipeline. */
    private fun createSession(context: Context): ScannerSession = ScannerSessionImpl(
        scanner = Scanner(
            camera = XCamera(context),
            analyzer = MlkitImageBarcodeAnalyzer(),
        ),
        mainHandler = mainHandler,
        onScanResult = scanResultSink::emit,
        onReleaseRequested = { session = null },
    ).also { newSession ->
        session = newSession
        updateSessionActivity(newSession)
    }

    private fun syncHostState(lifecycle: Lifecycle) {
        updateHostResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun updateHostResumed(resumed: Boolean) {
        if (hostResumed == resumed) return
        hostResumed = resumed
        session?.let(::updateSessionActivity)
    }

    private fun updateSessionActivity(target: ScannerSession) {
        if (hostResumed) target.activate() else target.deactivate()
    }
}
