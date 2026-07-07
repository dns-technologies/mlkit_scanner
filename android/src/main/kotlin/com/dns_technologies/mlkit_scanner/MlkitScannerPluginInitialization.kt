package com.dns_technologies.mlkit_scanner

import android.Manifest
import android.util.Log
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.ZoomNotSupportedException
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Coordinates permission checks and asynchronous scanner camera initialization. */
internal class MlkitScannerPluginInitialization(
    private val logTag: String,
    private val permissionGateway: PermissionGateway,
    private val scannerViewProvider: () -> ScannerView?,
) {
    /** Indicates whether the scanner has already completed initialization. */
    var isInitialized = false
        private set

    /** Handles the Dart request to initialize the scanner camera. */
    fun requestInitialization(call: MethodCall, result: Result) {
        if (isInitialized) {
            result.success(true)
            return
        }

        val parameters = call.initialParameters()
        permissionGateway.requestPermissions(
            permissions = arrayOf(Manifest.permission.CAMERA),
            onGranted = { initialize(parameters, result) },
            onDenied = {
                completeError(result, PluginError.AuthorizationCameraError.errorCode, ERROR_NO_CAMERA_PERMISSION, null)
            },
        )
    }

    /** Resets scanner initialization state after the active scanner is released. */
    fun reset() {
        isInitialized = false
    }

    /** Starts scanner initialization after permission checks are complete. */
    private fun initialize(parameters: InitialScannerParameters?, result: Result) {
        val scannerView = scannerViewProvider()
        if (scannerView == null) {
            completeError(result, PluginError.CameraIsNotInitialized.errorCode, ERROR_VIEW_NOT_CREATED, null)
            return
        }

        if (scannerView.isActive()) {
            completeInitialized(result)
            return
        }

        scannerView.startCamera(
            initialZoom = parameters?.zoom?.toFloat(),
            initialCropRect = parameters?.cropRect,
            onReady = { completeInitialized(result) },
            onError = { error ->
                Log.e(logTag, error.toString())
                if (error is ZoomNotSupportedException) {
                    completeError(result, PluginError.DeviceHasNotZoom.errorCode, ERROR_NO_ZOOM, null)
                } else {
                    completeError(result, PluginError.InitCameraError.errorCode, ERROR_INIT_CAMERA, error.message)
                }
            },
        )
    }

    /** Marks initialization as complete and replies to Dart. */
    private fun completeInitialized(result: Result) {
        isInitialized = true
        result.success(true)
    }

    /** Completes the initialization call with an error. */
    private fun completeError(result: Result, errorCode: String, errorMessage: String, errorDetails: Any?) {
        isInitialized = false
        result.error(errorCode, errorMessage, errorDetails)
    }

    /** Extracts initial scanner parameters from a method call. */
    private fun MethodCall.initialParameters(): InitialScannerParameters? {
        val args = arguments as Map<String, Any?>?
        return if (args != null) InitialScannerParameters(args) else null
    }

    private companion object {
        const val ERROR_VIEW_NOT_CREATED = "Camera platform view is not created"
        const val ERROR_NO_CAMERA_PERMISSION = "The app does not have camera permission"
        const val ERROR_NO_ZOOM = "Zoom is not supported on this device"
        const val ERROR_INIT_CAMERA = "Internal camera initialisation error"
    }
}
