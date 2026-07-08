package com.dns_technologies.mlkit_scanner.handlers

import android.Manifest
import android.util.Log
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.ZoomNotSupportedException
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters

/**
 * Describes an initialization failure that can be forwarded to Flutter.
 *
 * @property code Method channel error code.
 * @property message Human-readable error message.
 * @property details Optional error details passed through the method channel.
 */
internal data class ScannerInitializationError(
    val code: String,
    val message: String,
    val details: Any?,
)

/** Coordinates permission checks and asynchronous scanner camera initialization. */
internal class ScannerInitializationHandler(
    private val logTag: String,
    private val permissionGateway: PermissionGateway,
    private val scannerSessionProvider: () -> ScannerSession?,
) {
    /** Indicates whether the scanner has already completed initialization. */
    var isInitialized = false
        private set

    /** Handles the Dart request to initialize the scanner camera. */
    fun requestInitialization(
        parameters: InitialScannerParameters?,
        onInitialized: () -> Unit,
        onError: (ScannerInitializationError) -> Unit,
    ) {
        permissionGateway.requestPermissions(
            permissions = arrayOf(Manifest.permission.CAMERA),
            onGranted = { initialize(parameters, onInitialized, onError) },
            onDenied = {
                completeError(onError, PluginError.AuthorizationCameraError.errorCode, ERROR_NO_CAMERA_PERMISSION, null)
            },
        )
    }

    /** Resets scanner initialization state after the active scanner is released. */
    fun reset() {
        isInitialized = false
    }

    /** Starts scanner initialization after permission checks are complete. */
    private fun initialize(
        parameters: InitialScannerParameters?,
        onInitialized: () -> Unit,
        onError: (ScannerInitializationError) -> Unit,
    ) {
        val scannerView = scannerSessionProvider()?.scannerView
        if (scannerView == null) {
            completeError(onError, PluginError.CameraIsNotInitialized.errorCode, ERROR_VIEW_NOT_CREATED, null)
            return
        }

        if (scannerView.isActive()) {
            completeInitialized(onInitialized)
            return
        }

        scannerView.startCamera(
            initialZoom = parameters?.zoom?.toFloat(),
            initialCropRect = parameters?.cropRect,
            onReady = { completeInitialized(onInitialized) },
            onError = { error ->
                Log.e(logTag, error.toString())
                if (error is ZoomNotSupportedException) {
                    completeError(onError, PluginError.DeviceHasNotZoom.errorCode, ERROR_NO_ZOOM, null)
                } else {
                    completeError(onError, PluginError.InitCameraError.errorCode, ERROR_INIT_CAMERA, error.message)
                }
            },
        )
    }

    /** Marks initialization as complete. */
    private fun completeInitialized(onInitialized: () -> Unit) {
        isInitialized = true
        onInitialized.invoke()
    }

    /** Completes initialization with an error. */
    private fun completeError(
        onError: (ScannerInitializationError) -> Unit,
        errorCode: String,
        errorMessage: String,
        errorDetails: Any?,
    ) {
        reset()
        onError.invoke(ScannerInitializationError(errorCode, errorMessage, errorDetails))
    }

    private companion object {
        const val ERROR_VIEW_NOT_CREATED = "Camera platform view is not created"
        const val ERROR_NO_CAMERA_PERMISSION = "The app does not have camera permission"
        const val ERROR_NO_ZOOM = "Zoom is not supported on this device"
        const val ERROR_INIT_CAMERA = "Internal camera initialisation error"
    }
}
