package com.dns_technologies.mlkit_scanner.session

import android.content.Context
import android.os.Handler
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.mlkit.MlkitImageBarcodeAnalyzer
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
@MainThread
internal class ScannerSessionController(
    mainHandler: Handler,
    scanResultSink: ScanResultSink,
    private val sessionFactory: (Context, (ScannerSession) -> Unit) -> ScannerSession = { context, onRelease ->
        ScannerSessionImpl(
            scanner = Scanner(camera = XCamera(context), analyzer = MlkitImageBarcodeAnalyzer()),
            mainHandler = mainHandler,
            onScanResult = scanResultSink::emit,
            onReleaseRequested = onRelease,
        )
    },
) {
    var session: ScannerSession? = null
        private set

    private var hostLifecycle: Lifecycle? = null
    private var hostResumed = false
    private val hostLifecycleObserver = LifecycleEventObserver { source, event ->
        // A removed observer can still be present in an in-flight lifecycle dispatch.
        if (source.lifecycle !== hostLifecycle) return@LifecycleEventObserver
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

        val existingSession = session
        val activeSession = existingSession ?: sessionFactory(context) { released ->
            if (session === released) session = null
        }.also { session = it }
        try {
            if (existingSession == null) updateSessionActivity(activeSession)
            return activeSession.createView(
                context = context,
                viewId = viewId,
                initialZoomRatio = initialZoomRatio,
                initialCropRect = initialCropRect,
                initialFlashEnabled = initialFlashEnabled,
            )
        } catch (error: Exception) {
            // This call owns a newly created session until its first view succeeds.
            // Never tear down a pre-existing session because another view failed to register.
            if (existingSession == null) {
                if (session === activeSession) session = null
                try {
                    activeSession.release()
                } catch (cleanupError: Exception) {
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
    }

    /** Replaces the Activity lifecycle observed by this controller. */
    fun attachHostLifecycle(lifecycle: Lifecycle) {
        if (hostLifecycle === lifecycle) {
            syncHostState()
            return
        }
        val previous = hostLifecycle
        hostLifecycle = lifecycle
        previous?.removeObserver(hostLifecycleObserver)
        if (hostLifecycle !== lifecycle) return
        lifecycle.addObserver(hostLifecycleObserver)
        syncHostState()
    }

    /** Stops observing the Activity lifecycle and pauses the retained scanner session. */
    fun detachHostLifecycle() {
        val detached = hostLifecycle
        hostLifecycle = null
        detached?.removeObserver(hostLifecycleObserver)
        syncHostState()
    }

    /** Releases and forgets the current scanner session. */
    fun release() {
        val activeSession = session
        session = null
        activeSession?.release()
    }

    private fun syncHostState() {
        // Observer callbacks may have replaced or detached the host during attachment changes.
        val resumed = hostLifecycle
            ?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) == true
        updateHostResumed(resumed)
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
