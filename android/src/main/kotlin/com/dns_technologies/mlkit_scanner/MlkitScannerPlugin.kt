package com.dns_technologies.mlkit_scanner

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.commands.CancelScanCommand
import com.dns_technologies.mlkit_scanner.commands.CaptureCameraCommand
import com.dns_technologies.mlkit_scanner.commands.PauseCameraCommand
import com.dns_technologies.mlkit_scanner.commands.ReleaseCameraCommand
import com.dns_technologies.mlkit_scanner.commands.ResumeCameraCommand
import com.dns_technologies.mlkit_scanner.commands.SetCropAreaCommand
import com.dns_technologies.mlkit_scanner.commands.SetScanDelayCommand
import com.dns_technologies.mlkit_scanner.commands.SetZoomRatioCommand
import com.dns_technologies.mlkit_scanner.commands.StartScanCommand
import com.dns_technologies.mlkit_scanner.commands.ToggleFlashCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/** Android scanner plugin entry point. */
class MlkitScannerPlugin internal constructor(
    private val mainHandler: Handler,
) : FlutterPlugin, ActivityAware, MethodCallHandler {
    /** Constructor used by the Flutter embedding. */
    constructor() : this(Handler(Looper.getMainLooper()))

    private var channel: MethodChannel? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var commandScope = createCommandScope()

    @Volatile
    private var scannerSession: ScannerSession? = null

    private val permissionGateway = PermissionGateway()
    private val permissionResultListener =
        PluginRegistry.RequestPermissionsResultListener(permissionGateway::onPermissionResult)

    /** Lifecycle attached to the current Flutter activity binding. */
    private val ActivityPluginBinding.activityLifecycle: Lifecycle
        get() = FlutterLifecycleAdapter.getActivityLifecycle(this)

    /** Registers method channel and scanner platform view factory with the Flutter engine. */
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        if (!commandScope.isActive) commandScope = createCommandScope()
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
        detachActivity(isFinal = true)
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
        detachActivity(isFinal = true)
        disposeScanner()
    }

    /** Reattaches Activity dependencies after a configuration change. */
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    /** Temporarily detaches Activity dependencies before a configuration change reattach. */
    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity(isFinal = false)
    }

    /** Routes Flutter method channel calls to scanner initialization and command handlers. */
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            PluginConstants.captureCameraMethod -> CaptureCameraCommand(
                scannerSessionProvider = ::scannerSession,
                permissionGateway = permissionGateway,
                commandScope = commandScope,
            ).execute(call, result)
            PluginConstants.releaseCameraMethod ->
                ReleaseCameraCommand(::scannerSession).execute(call, result)
            PluginConstants.resumeCameraMethod -> ResumeCameraCommand(::scannerSession).execute(call, result)
            PluginConstants.pauseCameraMethod -> PauseCameraCommand(::scannerSession).execute(call, result)
            PluginConstants.toggleFlashMethod -> ToggleFlashCommand(
                scannerSessionProvider = ::scannerSession,
                commandScope = commandScope,
            ).execute(call, result)
            PluginConstants.startScanMethod -> StartScanCommand(::scannerSession).execute(call, result)
            PluginConstants.cancelScanMethod -> CancelScanCommand(::scannerSession).execute(call, result)
            PluginConstants.setScanDelayMethod -> SetScanDelayCommand(::scannerSession).execute(call, result)
            PluginConstants.setZoomRatioMethod -> SetZoomRatioCommand(
                scannerSessionProvider = ::scannerSession,
                commandScope = commandScope,
            ).execute(call, result)
            PluginConstants.setCropAreaMethod -> SetCropAreaCommand(::scannerSession).execute(call, result)
            else -> result.notImplemented()
        }
    }

    /** Attaches Activity-scoped permissions and lifecycle delegates. */
    private fun attachActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        permissionGateway.attach(binding)
        scannerSession?.attachHostLifecycle(binding.activityLifecycle)
        binding.addRequestPermissionsResultListener(permissionResultListener)
    }

    /** Detaches Activity-scoped permissions and lifecycle delegates. */
    private fun detachActivity(isFinal: Boolean) {
        val binding = activityBinding
        scannerSession?.detachHostLifecycle()
        binding?.removeRequestPermissionsResultListener(permissionResultListener)
        activityBinding = null
        if (isFinal) {
            permissionGateway.detachFinal()
        } else {
            permissionGateway.detachForConfigChange()
        }
    }

    /** Creates a platform view inside the one scanner session owned by this engine. */
    private fun createScannerView(
        context: android.content.Context,
        viewId: Int,
        creationParams: Any?,
    ): ScannerView {
        val registration = ScannerMethodArguments.viewRegistration(creationParams)
        if (registration.viewId != viewId) throw PluginError.InvalidArguments
        val session = scannerSession ?: ScannerSessionImpl(
            scanner = Scanner(
                camera = XCamera(context),
                analyzer = MlkitImageBarcodeAnalyzer(TAG),
            ),
            mainHandler = mainHandler,
            onScanResult = ::emitScanResult,
            onReleaseRequested = ::removeScannerSession,
        ).also { newSession ->
            scannerSession = newSession
            activityBinding?.activityLifecycle?.let(newSession::attachHostLifecycle)
        }
        return session.createView(
            context = context,
            viewId = viewId,
            initialZoomRatio = registration.initialZoomRatio,
            initialCropRect = registration.initialCropRect,
            initialFlashEnabled = registration.initialFlashEnabled,
        )
    }

    /** Releases the engine's scanner session and removes it as the active command target. */
    private fun disposeScanner() {
        val activeSession = scannerSession
        scannerSession = null
        activeSession?.release()
    }

    /** Removes only the session that started releasing, preserving a possible replacement. */
    private fun removeScannerSession(releasedSession: ScannerSession) {
        if (scannerSession === releasedSession) scannerSession = null
    }

    /** Sends a recognized barcode result to Dart on the main thread. */
    private fun emitScanResult(viewId: Int, result: Barcode) {
        channel?.invokeMethod(
            PluginConstants.scanResultMethod,
            mapOf(
                PluginConstants.viewIdArgument to viewId,
                PluginConstants.barcodeArgument to result.toMap(),
            ),
        )
    }

    private companion object {
        const val TAG = "MLKIT_SCANNER_PLUGIN"

        /** Creates a main-thread supervisor scope for asynchronous method-channel commands. */
        fun createCommandScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
