package com.dns_technologies.mlkit_scanner.controllers

import android.content.Context
import android.os.Handler
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.models.ScannerSessionImpl
import com.dns_technologies.mlkit_scanner.models.ScannerViewRegistration
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.x.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode

/** Delivers scanner results outside the scanner controller. */
internal fun interface ScanResultSink {
    fun emit(viewId: Int, result: Barcode)
}

/** Main-thread owner of the scanner session, its platform views, and host lifecycle attachment. */
internal class ScannerController(
    private val mainHandler: Handler,
    private val scanResultSink: ScanResultSink,
) {
    var session: ScannerSession? = null
        private set

    private var hostLifecycle: Lifecycle? = null

    /** Creates a platform view in the current scanner session, creating the session if necessary. */
    fun createView(
        context: Context,
        platformViewId: Int,
        creationParams: Any?,
    ): ScannerView {
        val registration = ScannerViewRegistration.from(creationParams)
        if (registration.viewId != platformViewId) throw PluginError.InvalidArguments

        val activeSession = session ?: createSession(context)
        return activeSession.createView(
            context = context,
            viewId = platformViewId,
            initialZoomRatio = registration.initialZoomRatio,
            initialCropRect = registration.initialCropRect,
            initialFlashEnabled = registration.initialFlashEnabled,
        )
    }

    /** Remembers and attaches the lifecycle supplied by the current Flutter Activity. */
    fun attachHostLifecycle(lifecycle: Lifecycle) {
        hostLifecycle = lifecycle
        session?.attachHostLifecycle(lifecycle)
    }

    /** Detaches the current Activity lifecycle while preserving the scanner session. */
    fun detachHostLifecycle() {
        session?.detachHostLifecycle()
        hostLifecycle = null
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
            analyzer = MlkitImageBarcodeAnalyzer(TAG),
        ),
        mainHandler = mainHandler,
        onScanResult = scanResultSink::emit,
        onReleaseRequested = { session = null },
    ).also { newSession ->
        session = newSession
        hostLifecycle?.let(newSession::attachHostLifecycle)
    }

    private companion object {
        const val TAG = "MLKIT_SCANNER_PLUGIN"
    }
}
