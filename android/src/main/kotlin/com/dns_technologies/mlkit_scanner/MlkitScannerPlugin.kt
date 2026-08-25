package com.dns_technologies.mlkit_scanner

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.commands.CancelScanCommand
import com.dns_technologies.mlkit_scanner.commands.DisposeCameraCommand
import com.dns_technologies.mlkit_scanner.commands.InitCameraCommand
import com.dns_technologies.mlkit_scanner.commands.PauseCameraCommand
import com.dns_technologies.mlkit_scanner.commands.ResumeCameraCommand
import com.dns_technologies.mlkit_scanner.commands.SetCropAreaCommand
import com.dns_technologies.mlkit_scanner.commands.SetScanDelayCommand
import com.dns_technologies.mlkit_scanner.commands.SetZoomCommand
import com.dns_technologies.mlkit_scanner.commands.StartScanCommand
import com.dns_technologies.mlkit_scanner.commands.ToggleFlashCommand
import com.dns_technologies.mlkit_scanner.commands.UpdateConstraintsCommand
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.models.ScannerSessionImpl
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.ScannerViewFactory
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.x.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** Android plugin entry point for the ML Kit scanner. */
class MlkitScannerPlugin internal constructor(
    private val mainHandler: Handler,
) : FlutterPlugin, ActivityAware, MethodCallHandler, DefaultLifecycleObserver {
    /** Constructor used by the Flutter embedding. */
    constructor() : this(Handler(Looper.getMainLooper()))

    private var channel: MethodChannel? = null
    private var activityBinding: ActivityPluginBinding? = null
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var scannerSession: ScannerSession? = null

    private val permissionGateway = PermissionGateway()

    /** Lifecycle attached to the current Flutter activity binding. */
    private val ActivityPluginBinding.activityLifecycle: Lifecycle
        get() = (lifecycle as HiddenLifecycleReference).lifecycle

    /** Registers method channel and scanner platform view factory with the Flutter engine. */
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, PluginConstants.channelName)
        channel = methodChannel
        methodChannel.setMethodCallHandler(this)
        flutterPluginBinding
            .platformViewRegistry
            .registerViewFactory(
                PluginConstants.cameraPlatformViewName,
                ScannerViewFactory(::createScannerView),
            )
    }

    /** Releases scanner state and disconnects the method channel from the Flutter engine. */
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        disposeScanner()
        commandScope.cancel()
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
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            PluginConstants.initCameraMethod -> InitCameraCommand(
                scannerSessionProvider = ::scannerSession,
                permissionGateway = permissionGateway,
                commandScope = commandScope,
            ).execute(call, result)
            PluginConstants.resumeCameraMethod -> ResumeCameraCommand(::scannerSession).execute(call, result)
            PluginConstants.pauseCameraMethod -> PauseCameraCommand(::scannerSession).execute(call, result)
            PluginConstants.disposeCameraMethod -> DisposeCameraCommand(::scannerSession).execute(call, result)
            PluginConstants.toggleFlashMethod -> ToggleFlashCommand(::scannerSession).execute(call, result)
            PluginConstants.startScanMethod -> StartScanCommand(::scannerSession).execute(call, result)
            PluginConstants.cancelScanMethod -> CancelScanCommand(::scannerSession).execute(call, result)
            PluginConstants.setScanDelayMethod -> SetScanDelayCommand(::scannerSession).execute(call, result)
            PluginConstants.updateConstraintsMethod -> UpdateConstraintsCommand().execute(call, result)
            PluginConstants.setZoomMethod -> SetZoomCommand(::scannerSession).execute(call, result)
            PluginConstants.setCropAreaMethod -> SetCropAreaCommand(::scannerSession).execute(call, result)
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

    /** Creates a platform view inside the one scanner session owned by this engine. */
    private fun createScannerView(context: android.content.Context, viewId: Int): ScannerView {
        val session = scannerSession ?: ScannerSessionImpl(
            scanner = Scanner(
                camera = XCamera(context),
                analyzer = MlkitImageBarcodeAnalyzer(TAG),
            ),
            mainHandler = mainHandler,
            onScanResult = ::emitScanResult,
            onReleased = { scannerSession = null },
        ).also { scannerSession = it }
        return session.createView(context, viewId)
    }

    private fun disposeScanner() {
        val activeSession = scannerSession
        scannerSession = null
        activeSession?.release()
    }

    /** Sends a recognized barcode result to Dart on the main thread. */
    private fun emitScanResult(result: Barcode) {
        channel?.invokeMethod(PluginConstants.scanResultMethod, result.toMap())
    }

    private companion object {
        const val TAG = "MLKIT_SCANNER_PLUGIN"
    }
}
