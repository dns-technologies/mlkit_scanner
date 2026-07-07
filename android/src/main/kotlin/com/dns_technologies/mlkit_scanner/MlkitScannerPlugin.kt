package com.dns_technologies.mlkit_scanner

import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.ScannerViewFactory
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** Android plugin entry point for the ML Kit scanner. */
class MlkitScannerPlugin : FlutterPlugin, ActivityAware, MethodCallHandler, DefaultLifecycleObserver {
    private lateinit var channel: MethodChannel
    private lateinit var activityBinding: ActivityPluginBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionGateway = PermissionGateway()
    private val commands = MlkitScannerPluginCommands(::scannerView)
    private val initialization = MlkitScannerPluginInitialization(
        logTag = TAG,
        permissionGateway = permissionGateway,
        scannerViewProvider = ::scannerView,
    )
    private var scannerView: ScannerView? = null
    private var unsubscribeFromScanResults: (() -> Unit)? = null
    private var isCameraDesiredActive = false

    private val activityLifecycle: Lifecycle
        get() = (activityBinding.lifecycle as HiddenLifecycleReference).lifecycle

    private val initializedChannel: MethodChannel?
        get() = channel.takeIf { initialization.isInitialized }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, PluginConstants.channelName)
        channel.setMethodCallHandler(this)
        flutterPluginBinding
            .platformViewRegistry
            .registerViewFactory(
                PluginConstants.cameraPlatformViewName,
                ScannerViewFactory(
                    createCamera = ::XCamera,
                    createImageAnalyzer = { MlkitImageBarcodeAnalyzer(TAG) },
                    onCreate = ::attachScannerView,
                    onDispose = { disposedScannerView ->
                        if (disposedScannerView === scannerView) {
                            releaseScanner()
                        }
                    },
                ),
            )
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        releaseScanner()
        mainHandler.removeCallbacksAndMessages(null)
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            PluginConstants.initCameraMethod -> {
                isCameraDesiredActive = true
                initialization.requestInitialization(call, result)
            }
            PluginConstants.resumeCameraMethod -> {
                isCameraDesiredActive = true
                scannerView?.resumeCamera()
                result.success(true)
            }
            PluginConstants.pauseCameraMethod -> {
                isCameraDesiredActive = false
                scannerView?.pauseCamera()
                result.success(true)
            }
            PluginConstants.disposeCameraMethod -> {
                releaseScanner()
                result.success(true)
            }
            PluginConstants.toggleFlashMethod -> commands.toggleFlash(result)
            PluginConstants.startScanMethod -> commands.startScan(call, result)
            PluginConstants.cancelScanMethod -> commands.cancelScan(result)
            PluginConstants.setScanDelayMethod -> commands.setScanDelay(call, result)
            PluginConstants.updateConstraintsMethod -> result.success(true)
            PluginConstants.setZoomMethod -> commands.setZoom(call, result)
            PluginConstants.setCropAreaMethod -> commands.setCropArea(call, result)
            else -> result.notImplemented()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (isCameraDesiredActive) {
            scannerView?.resumeCamera()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        val activeScannerView = scannerView?.takeIf { it.isActive() } ?: return
        activeScannerView.pauseCamera()
    }

    /** Attaches Activity-scoped permissions and lifecycle delegates. */
    private fun attachActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        permissionGateway.attach(binding)
        activityLifecycle.addObserver(this)
        binding.addRequestPermissionsResultListener(permissionGateway::onPermissionResult)
    }

    /** Detaches Activity-scoped permissions and lifecycle delegates. */
    private fun detachActivity() {
        activityLifecycle.removeObserver(this)
        activityBinding.removeRequestPermissionsResultListener(permissionGateway::onPermissionResult)
        permissionGateway.detach()
    }

    /** Stores a created scanner platform view and subscribes to scan results. */
    private fun attachScannerView(scannerView: ScannerView) {
        this.scannerView = scannerView
        unsubscribeFromScanResults?.invoke()
        unsubscribeFromScanResults = scannerView.subscribeToScanResults(::sendScanResult)
    }

    /** Releases scanner resources and clears initialization state. */
    private fun releaseScanner() {
        initialization.reset()
        unsubscribeFromScanResults?.invoke()
        unsubscribeFromScanResults = null
        scannerView?.releaseCamera()
        scannerView = null
        isCameraDesiredActive = false
    }

    /** Sends a recognized barcode result to Dart. */
    private fun sendScanResult(result: Barcode) {
        val payload = result.toMap()
        mainHandler.post {
            initializedChannel?.invokeMethod(PluginConstants.scanResultMethod, payload)
        }
    }

    private companion object {
        const val TAG = "MLKIT_SCANNER_PLUGIN"
    }
}
