package com.dns_technologies.mlkit_scanner

import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.handlers.ScannerCommandHandler
import com.dns_technologies.mlkit_scanner.handlers.ScannerInitializationHandler
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.ScannerViewFactory
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
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
    private var channel: MethodChannel? = null
    private var activityBinding: ActivityPluginBinding? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scannerSession: ScannerSession? = null
    private val staleScannerSessions = mutableListOf<ScannerSession>()

    private val permissionGateway = PermissionGateway()
    private val commands = ScannerCommandHandler(::scannerSession)
    private val initialization = ScannerInitializationHandler(
        logTag = TAG,
        permissionGateway = permissionGateway,
        scannerSessionProvider = ::scannerSession,
    )

    /** Lifecycle attached to the current Flutter activity binding. */
    private val ActivityPluginBinding.activityLifecycle: Lifecycle
        get() = (lifecycle as HiddenLifecycleReference).lifecycle

    /** Registers method channel and scanner platform view factory with the Flutter engine. */
    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, PluginConstants.channelName)
        channel = methodChannel
        methodChannel.setMethodCallHandler(this)
        flutterPluginBinding
            .platformViewRegistry
            .registerViewFactory(
                PluginConstants.cameraPlatformViewName,
                ScannerViewFactory(
                    createCamera = ::XCamera,
                    createImageAnalyzer = { MlkitImageBarcodeAnalyzer(TAG) },
                    onCreate = ::bindScannerView,
                    onDispose = ::unbindScannerView,
                ),
            )
    }

    /** Releases scanner state and disconnects the method channel from the Flutter engine. */
    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        disposeScanner()
        mainHandler.removeCallbacksAndMessages(null)
        channel?.setMethodCallHandler(null)
        channel = null
    }

    /** Attaches Android Activity dependencies required by scanner permissions and lifecycle. */
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    /** Detaches Android Activity dependencies when the plugin loses its Activity. */
    override fun onDetachedFromActivity() {
        detachActivity()
    }

    /** Reattaches Activity dependencies after a configuration change. */
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    /** Temporarily detaches Activity dependencies before a configuration change reattach. */
    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    /** Routes Flutter method channel calls to scanner initialization and command handlers. */
    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            PluginConstants.initCameraMethod -> initializeScanner(call, result)
            PluginConstants.resumeCameraMethod -> {
                scannerSession?.resumeCamera()
                result.success(true)
            }
            PluginConstants.pauseCameraMethod -> {
                scannerSession?.pauseCamera()
                result.success(true)
            }
            PluginConstants.disposeCameraMethod -> {
                disposeScanner()
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

    /** Handles host lifecycle resume events for the active scanner session. */
    override fun onResume(owner: LifecycleOwner) {
        scannerSession?.onHostResume()
    }

    /** Handles host lifecycle pause events for the active scanner session. */
    override fun onPause(owner: LifecycleOwner) {
        scannerSession?.onHostPause()
    }

    /** Attaches Activity-scoped permissions and lifecycle delegates. */
    private fun attachActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        permissionGateway.attach(binding)
        binding.activityLifecycle.addObserver(this)
        binding.addRequestPermissionsResultListener(permissionGateway::onPermissionResult)
    }

    /** Detaches Activity-scoped permissions and lifecycle delegates. */
    private fun detachActivity() {
        val binding = activityBinding ?: return
        binding.activityLifecycle.removeObserver(this)
        binding.removeRequestPermissionsResultListener(permissionGateway::onPermissionResult)
        activityBinding = null
        permissionGateway.detach()
    }

    /** Binds a created platform view to a new active scanner session. */
    private fun bindScannerView(scannerView: ScannerView) {
        scannerSession?.let(staleScannerSessions::add)
        scannerSession = ScannerSession(scannerView, ::emitScanResult)
        initialization.reset()
    }

    /** Initializes the active scanner session after Dart requests camera startup. */
    private fun initializeScanner(call: MethodCall, result: Result) {
        if (initialization.isInitialized) {
            result.success(true)
            return
        }

        val args = call.arguments as Map<String, Any?>?
        initialization.requestInitialization(
            parameters = if (args != null) InitialScannerParameters(args) else null,
            onInitialized = { result.success(true) },
            onError = { error -> result.error(error.code, error.message, error.details) },
        )
    }

    /** Releases the active scanner session and clears initialization state. */
    private fun disposeScanner() {
        val activeSession = scannerSession
        val staleSessions = staleScannerSessions.toList()

        scannerSession = null
        staleScannerSessions.clear()

        activeSession?.release()
        staleSessions.forEach { it.release() }
        initialization.reset()
    }

    /** Unbinds a disposed scanner platform view without disturbing a newer active session. */
    private fun unbindScannerView(scannerView: ScannerView) {
        val activeSession = scannerSession
        if (activeSession?.owns(scannerView) == true) {
            activeSession.release()
            scannerSession = null
            initialization.reset()
            return
        }

        val staleSession = staleScannerSessions.firstOrNull { it.owns(scannerView) }
        if (staleSession != null) {
            staleSession.release()
            staleScannerSessions -= staleSession
            return
        }

        scannerView.dispose()
    }

    /** Sends a recognized barcode result to Dart on the main thread. */
    private fun emitScanResult(result: Barcode) {
        val payload = result.toMap()
        mainHandler.post {
            channel?.invokeMethod(PluginConstants.scanResultMethod, payload)
        }
    }

    private companion object {
        const val TAG = "MLKIT_SCANNER_PLUGIN"
    }
}
