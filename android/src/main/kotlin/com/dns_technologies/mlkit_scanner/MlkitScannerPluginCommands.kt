package com.dns_technologies.mlkit_scanner

import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.HasNoFlashUnitException
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.ZoomNotSupportedException
import com.dns_technologies.mlkit_scanner.scanner.models.AnalyzeOptions
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Executes scanner commands received from the Flutter method channel. */
internal class MlkitScannerPluginCommands(
    private val session: MlkitScannerPluginSession,
) {
    /** Handles the Dart request to toggle camera flash. */
    fun toggleFlash(result: Result) {
        val scannerView = activeScannerView(result, ERROR_FLASH_REQUIRES_INIT) ?: return
        try {
            scannerView.toggleFlashLight()
            result.success(true)
        } catch (e: HasNoFlashUnitException) {
            result.error(PluginError.DeviceHasNotFlash.errorCode, ERROR_NO_FLASH, null)
        }
    }

    /** Handles the Dart request to start barcode scanning. */
    fun startScan(call: MethodCall, result: Result) {
        val scannerView = activeScannerView(result, ERROR_SCAN_REQUIRES_INIT) ?: return
        scannerView.startScan(AnalyzeOptions.fromMap(call.arguments as Map<String, Any?>).periodMs)
        result.success(true)
    }

    /** Handles the Dart request to pause barcode scanning. */
    fun cancelScan(result: Result) {
        session.scannerView?.pauseScan()
        result.success(true)
    }

    /** Handles the Dart request to update scanner analysis delay. */
    fun setScanDelay(call: MethodCall, result: Result) {
        val delay = call.arguments
        if (delay !is Number) {
            result.error(PluginError.InvalidArguments.errorCode, ERROR_INVALID_NUMBER, null)
            return
        }
        session.scannerView?.updateScanPeriod(delay.toInt())
        result.success(true)
    }

    /** Handles the Dart request to update camera zoom. */
    fun setZoom(call: MethodCall, result: Result) {
        val scannerView = activeScannerView(result, ERROR_ZOOM_REQUIRES_INIT) ?: return
        val value = call.arguments
        if (value !is Double) {
            result.error(PluginError.InvalidArguments.errorCode, ERROR_INVALID_DOUBLE, null)
            return
        }

        try {
            scannerView.setZoom(value.toFloat())
            result.success(true)
        } catch (e: ZoomNotSupportedException) {
            result.error(PluginError.DeviceHasNotZoom.errorCode, ERROR_NO_ZOOM, null)
        }
    }

    /** Handles the Dart request to update scanner crop area. */
    fun setCropArea(call: MethodCall, result: Result) {
        session.scannerView?.setCropArea(RecognizeVisorCropRect.fromMap(call.arguments as Map<String, Any?>))
        result.success(true)
    }

    /** Returns the active scanner view or reports a command-specific initialization error. */
    private fun activeScannerView(result: Result, errorMessage: String): ScannerView? {
        return session.activeScannerViewOrNull() ?: run {
            result.error(PluginError.CameraIsNotInitialized.errorCode, errorMessage, null)
            null
        }
    }

    private companion object {
        const val ERROR_FLASH_REQUIRES_INIT = "You need to invoke the 'initCameraPreview' method before using flash"
        const val ERROR_SCAN_REQUIRES_INIT = "You need to invoke 'initCameraPreview' method before start scan"
        const val ERROR_ZOOM_REQUIRES_INIT = "You need to invoke the 'initCameraPreview' method before using zoom"
        const val ERROR_NO_FLASH = "Device has no flash"
        const val ERROR_NO_ZOOM = "Zoom is not supported on this device"
        const val ERROR_INVALID_NUMBER = "Invalid argument passed, Number type is expected"
        const val ERROR_INVALID_DOUBLE = "Invalid argument passed, Double type is expected"
    }
}
