package com.dns_technologies.mlkit_scanner

import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.HasNoFlashUnitException
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.ZoomNotSupportedException
import com.dns_technologies.mlkit_scanner.scanner.components.camera.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.PermissionsConstants
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.ScannerViewFactory
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.Focus
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.FocusController
import com.dns_technologies.mlkit_scanner.scanner.models.AnalyzeOptions

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlin.Exception

/**
 * Android plugin for working with ML Kit scanner
 *
 * CameraX is used for camera preview and image analysis.
 * Plugin inherits [ActivityAware] for checking camera user permissions
 */
class MlkitScannerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, LifecycleObserver {
    private lateinit var channel: MethodChannel
    private lateinit var binding: ActivityPluginBinding
    private var scannerView: ScannerView? = null

    /** Pending Dart init call that waits for permissions and asynchronous camera startup. */
    private var pendingInitialization: PendingScannerInitialization? = null
    private var scanResultSubscription: (() -> Unit)? = null

    private var isLockedAutoResumeCamera: Boolean = false

    private var isAlreadyInitialized: Boolean = false

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, PluginConstants.channelName)
        channel.setMethodCallHandler(this)
        flutterPluginBinding
            .platformViewRegistry
            .registerViewFactory(PluginConstants.cameraPlatformViewName,
                ScannerViewFactory(
                    createCamera = ::XCamera,
                    createImageAnalyzer = ::MlkitImageBarcodeAnalyzer,
                    createFocus = { context -> Focus(context, FocusController.INITIAL_FOCUS_CENTER) },
                    onCreate = {
                        scannerView = it
                        scanResultSubscription?.invoke()
                        scanResultSubscription = it.observeScanResults(this::onScan)
                    },
                    onDispose = this::onPlatformViewDisposed,
                ))
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            PluginConstants.initCameraMethod -> invokeInit(call, result)
            PluginConstants.resumeCameraMethod -> resumeScanner(result)
            PluginConstants.pauseCameraMethod -> pauseScanner(result)
            PluginConstants.disposeCameraMethod -> invokeDispose(result)
            PluginConstants.toggleFlashMethod -> invokeToggleFlash(result)
            PluginConstants.startScanMethod -> invokeStartScan(call, result)
            PluginConstants.cancelScanMethod -> invokeCancelScan(result)
            PluginConstants.setScanDelayMethod -> invokeSetScanDelay(call, result)
            PluginConstants.updateConstraintsMethod -> result.success(true) // на Android нет необходимости обрабатывать
            PluginConstants.setZoomMethod -> invokeSetZoom(call, result)
            PluginConstants.setCropAreaMethod -> invokeSetCropArea(call, result)
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onDetachedFromActivity() {
        val activityLifecycle = (binding.lifecycle as HiddenLifecycleReference).lifecycle
        activityLifecycle.removeObserver(this)
        binding.removeRequestPermissionsResultListener(this::listenPermissionResult)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addRequestPermissionsResultListener(this::listenPermissionResult)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        binding.removeRequestPermissionsResultListener(this::listenPermissionResult)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        val activityLifecycle = (binding.lifecycle as HiddenLifecycleReference).lifecycle
        activityLifecycle.addObserver(this)
        binding.addRequestPermissionsResultListener(this::listenPermissionResult)
    }

    /** Handles Android permission request results for camera access. */
    @Suppress("UNUSED_PARAMETER")
    private fun listenPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == PermissionsConstants.REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initializeScanner()
            } else {
                completeInitializationWithError(
                    PluginError.AuthorizationCameraError.errorCode,
                    "The app does not have camera permission",
                    null
                )
            }
        }
        return true
    }

    /** Handles the Dart request to initialize the scanner camera. */
    private fun invokeInit(call: MethodCall, result: Result) {
        // When rebuilding a widget, dispose() is not called,
        // which causes situations where initialization can be called multiple times.
        if (isAlreadyInitialized) {
            result.success(true)
            return
        }
        val args = call.arguments as Map<String, Any?>?
        pendingInitialization = PendingScannerInitialization(
            result = result,
            parameters = if (args != null) InitialScannerParameters(args) else null,
        )

        if (allPermissionsGranted()) {
            initializeScanner()
        } else {
            requestAllPermission()
        }
    }

    /** Handles the Dart request to resume scanner camera lifecycle. */
    private fun resumeScanner(result: Result) {
        isLockedAutoResumeCamera = false
        scannerView?.resumeCamera()
        result.success(true)
    }

    /** Handles the Dart request to pause scanner camera lifecycle. */
    private fun pauseScanner(result: Result) {
        isLockedAutoResumeCamera = true
        scannerView?.pauseCamera()
        result.success(true)
    }

    /** Handles the Dart request to toggle camera flash. */
    private fun invokeToggleFlash(result: Result) {
        withActiveScannerView(result, ERROR_FLASH_REQUIRES_INIT) { scannerView ->
            try {
                scannerView.toggleFlashLight()
                result.success(true)
            } catch (e: HasNoFlashUnitException) {
                result.error(
                    PluginError.DeviceHasNotFlash.errorCode,
                    "Device has no flash",
                    null
                )
            }
        }
    }

    /** Handles the Dart request to start barcode scanning. */
    private fun invokeStartScan(call: MethodCall, result: Result) {
        val options = AnalyzeOptions.fromMap(call.arguments as Map<String, Any?>)
        withActiveScannerView(result, ERROR_SCAN_REQUIRES_INIT) { scannerView ->
            scannerView.startScan(options.periodMs)
            result.success(true)
        }
    }

    /** Handles the Dart request to pause barcode scanning. */
    private fun invokeCancelScan(result: Result) {
        scannerView?.pauseScan()
        result.success(true)
    }

    /** Handles the Dart request to update scanner analysis delay. */
    private fun invokeSetScanDelay(call: MethodCall, result: Result) {
        val delay = call.arguments
        if (delay !is Number) {
            result.error(
                PluginError.InvalidArguments.errorCode,
                "Invalid argument passed, Number type is expected",
                null
            )
            return
        }
        scannerView?.updateScanPeriod(delay as Int)
        result.success(true)
    }

    /** Handles the Dart request to release scanner resources. */
    private fun invokeDispose(result: Result) {
        disposeCameraResources()
        result.success(true)
    }

    /** Releases plugin state when the active platform view is disposed. */
    private fun onPlatformViewDisposed(disposedScannerView: ScannerView) {
        if (disposedScannerView === scannerView) {
            disposeCameraResources()
            scannerView = null
        }
    }

    /** Clears scanner resources, subscriptions and pending initialization state. */
    private fun disposeCameraResources() {
        completeInitializationWithSuccess(false)
        scanResultSubscription?.invoke()
        scanResultSubscription = null
        scannerView?.releaseCamera()
        isAlreadyInitialized = false
    }

    /** Starts scanner initialization after permission checks are complete. */
    private fun initializeScanner() {
        if (scannerView == null) {
            completeInitializationWithError(
                PluginError.CameraIsNotInitialized.errorCode,
                ERROR_SCANNER_VIEW_NOT_CREATED,
                null
            )
            return
        }
        isLockedAutoResumeCamera = false
        startScanner()
    }

    /** Starts camera initialization or completes immediately when it is already active. */
    private fun startScanner() {
        val scannerView = scannerView ?: return
        if (!scannerView.isActive()) {
            // Some devices can change zoom before camera is initialized.
            // This is the reason why this method is called twice.
            val initialZoom = pendingInitialization?.parameters?.zoom
            if (initialZoom != null) {
                trySetZoom(
                    scannerView,
                    initialZoom,
                    result = null
                )
            }
            scannerView.startCamera(this::onInitSuccess, this::onInitError)
        } else {
            completeInitializationWithSuccess(true)
            isAlreadyInitialized = true
        }
    }

    /** Applies pending init parameters and completes the pending init call. */
    private fun onInitSuccess() {
        val scannerView = scannerView ?: return
        val parameters = pendingInitialization?.parameters
        val initialZoom = parameters?.zoom
        if (initialZoom != null) {
            if (!trySetZoom(scannerView, initialZoom, result = null)) {
                completeInitializationWithError(
                    PluginError.DeviceHasNotZoom.errorCode,
                    "Zoom is not supported on this device",
                    null
                )
                return
            }
        }
        if (parameters?.cropRect != null) {
            scannerView.setCropArea(parameters.cropRect)
        }

        completeInitializationWithSuccess(true)
        isAlreadyInitialized = true
    }

    /** Completes pending initialization with a camera init error. */
    private fun onInitError(e: Exception) {
        Log.e(TAG, e.toString())
        completeInitializationWithError(
            PluginError.InitCameraError.errorCode,
            "Internal camera initialisation error",
            e.message
        )
    }

    /** Completes the pending initialization call with success once. */
    private fun completeInitializationWithSuccess(value: Boolean) {
        val initialization = pendingInitialization ?: return
        pendingInitialization = null
        initialization.success(value)
    }

    /** Completes the pending initialization call with an error once. */
    private fun completeInitializationWithError(errorCode: String, errorMessage: String, errorDetails: Any?) {
        val initialization = pendingInitialization ?: return
        pendingInitialization = null
        initialization.error(errorCode, errorMessage, errorDetails)
    }

    /** Handles the Dart request to update camera zoom. */
    private fun invokeSetZoom(call: MethodCall, result: Result) {
        withActiveScannerView(result, ERROR_ZOOM_REQUIRES_INIT) { scannerView ->
            val value = call.arguments
            if (value !is Double) {
                result.error(
                    PluginError.InvalidArguments.errorCode,
                    "Invalid argument passed, Double type is expected",
                    null
                )
                return@withActiveScannerView
            }
            if (trySetZoom(scannerView, value, result = result)) {
                result.success(true)
            }
        }
    }

    /** Attempts to apply zoom and optionally reports zoom errors to Dart. */
    private fun trySetZoom(scannerView: ScannerView, value: Double, result: Result?): Boolean {
        return try {
            scannerView.setZoom(value.toFloat())
            true
        } catch (e: ZoomNotSupportedException) {
            result?.error(
                PluginError.DeviceHasNotZoom.errorCode,
                "Zoom is not supported on this device",
                null
            )
            false
        }
    }

    /** Handles the Dart request to update scanner crop area. */
    private fun invokeSetCropArea(call: MethodCall, result: Result) {
        val rect = RecognizeVisorCropRect.fromMap(call.arguments as Map<String, Any?>)
        scannerView?.setCropArea(rect)
        result.success(true)
    }

    /** Runs an action only when the scanner view exists and its camera is active. */
    private fun withActiveScannerView(result: Result, errorMessage: String, action: (ScannerView) -> Unit) {
        val scannerView = scannerView
        if (scannerView?.isActive() != true) {
            result.error(PluginError.CameraIsNotInitialized.errorCode, errorMessage, null)
            return
        }
        action(scannerView)
    }

    /** Returns true when all scanner Android permissions are granted. */
    private fun allPermissionsGranted() = PermissionsConstants.REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            binding.activity.baseContext,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Requests all Android permissions required by the scanner. */
    private fun requestAllPermission() = ActivityCompat.requestPermissions(
        binding.activity,
        PermissionsConstants.REQUIRED_PERMISSIONS,
        PermissionsConstants.REQUEST_CODE_PERMISSIONS
    )

    /** Sends a recognized barcode result to Dart. */
    private fun onScan(result: String) =
        channel.invokeMethod(PluginConstants.scanResultMethod, result.toBarcodeJson())

    /** Converts a raw barcode value into the legacy Dart barcode payload shape. */
    private fun String.toBarcodeJson(): Map<String, Any?> = mapOf(
        "raw_value" to this,
        "display_value" to this,
        "format" to UNKNOWN_BARCODE_FORMAT,
        "value_type" to UNKNOWN_BARCODE_VALUE_TYPE,
    )

    /** Resumes scanner camera lifecycle with the host activity. */
    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private fun onResume() {
        if (isLockedAutoResumeCamera) return
        scannerView?.resumeCamera()
    }

    /** Pauses scanner camera lifecycle with the host activity. */
    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private fun onPause() {
        if (scannerView?.isActive() == true) {
            scannerView?.pauseCamera()
        }
    }

    private companion object {
        const val TAG = "MLKIT_SCANNER_PLUGIN"
        const val UNKNOWN_BARCODE_FORMAT = 0
        const val UNKNOWN_BARCODE_VALUE_TYPE = 0
        const val ERROR_SCANNER_VIEW_NOT_CREATED = "Camera platform view is not created"
        const val ERROR_FLASH_REQUIRES_INIT = "You need to invoke the 'initCameraPreview' method before using flash"
        const val ERROR_SCAN_REQUIRES_INIT = "You need to invoke 'initCameraPreview' method before start scan"
        const val ERROR_ZOOM_REQUIRES_INIT = "You need to invoke the 'initCameraPreview' method before using zoom"
    }
}

private class PendingScannerInitialization(
    private val result: Result,
    val parameters: InitialScannerParameters?,
) {
    fun success(value: Boolean) {
        result.success(value)
    }

    fun error(errorCode: String, errorMessage: String, errorDetails: Any?) {
        result.error(errorCode, errorMessage, errorDetails)
    }
}
