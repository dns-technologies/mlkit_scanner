package com.dns_technologies.mlkit_scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.commands.SetZoomRatioCommand
import com.dns_technologies.mlkit_scanner.commands.ToggleFlashCommand
import com.dns_technologies.mlkit_scanner.commands.StartScanCommand
import com.dns_technologies.mlkit_scanner.commands.CancelScanCommand
import com.dns_technologies.mlkit_scanner.commands.SetScanDelayCommand
import com.dns_technologies.mlkit_scanner.commands.SetCropAreaCommand
import com.dns_technologies.mlkit_scanner.commands.base.reportScannerError
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerConfiguration
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.ScannerViewFactory
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.mlkit.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.x.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.utils.ExceptionCollector
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Flutter transport, Activity dependencies and one lazily created scanner. */
@MainThread
class MlkitScannerPlugin internal constructor(
    mainHandler: Handler,
    channel: MethodChannel? = null,
    private val scannerFactory: (Context, (Scanner, Throwable) -> Unit, (Int, Barcode) -> Unit) -> Scanner =
        { context, released, result -> createScanner(context, mainHandler, released, result) },
    private var commandScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val permissionGateway: PermissionGateway = PermissionGateway(),
) : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    constructor() : this(Handler(Looper.getMainLooper()))

    internal val isDisposed: Boolean get() = channel == null

    /** Detached before terminal callbacks. */
    private var channel: MethodChannel? = channel

    /** Created on capture; Flutter owns platform-view lifetimes. */
    private var scanner: Scanner? = null

    /** Routing references only; Flutter owns platform-view lifetimes. */
    private val views = mutableMapOf<Int, ScannerView>()

    /** Exact Flutter attachment used to unregister permission callbacks. */
    private var activityBinding: ActivityPluginBinding? = null

    private val permissionResultListener =
        PluginRegistry.RequestPermissionsResultListener(permissionGateway::onPermissionResult)
    private val ActivityPluginBinding.activityLifecycle: Lifecycle
        get() = FlutterLifecycleAdapter.getActivityLifecycle(this)

    init {
        channel?.setMethodCallHandler(this)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        check(channel == null) { "Scanner plugin is already attached to an engine" }
        commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val connected = MethodChannel(binding.binaryMessenger, PluginConstants.channelName)
        channel = connected
        connected.setMethodCallHandler(this)
        try {
            val factory = ScannerViewFactory { context, id ->
                check(channel === connected) { "Scanner engine is detached" }
                createView(context, id)
            }
            check(
                binding.platformViewRegistry.registerViewFactory(
                    PluginConstants.cameraPlatformViewName,
                    factory
                )
            ) {
                "Scanner platform view factory is already registered"
            }
        } catch (error: Exception) {
            ExceptionCollector(error).attempt(::dispose)
            throw error
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) = dispose()
    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attach(binding)
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        attach(binding)

    override fun onDetachedFromActivityForConfigChanges() = detach(isFinal = false)
    override fun onDetachedFromActivity() = detach(isFinal = true)

    /** Dart owns command admission; native controls address the single scanner. */
    internal fun currentScanner(): Scanner? =
        scanner?.takeIf { !isDisposed && !it.isDisposed }

    internal fun attach(binding: ActivityPluginBinding) {
        check(!isDisposed) { "Cannot attach an Activity to a disposed scanner plugin" }
        activityBinding = binding
        binding.addRequestPermissionsResultListener(permissionResultListener)
        scanner?.attachActivity(binding.activityLifecycle)
        permissionGateway.attach(binding.activity)
    }

    internal fun detach(isFinal: Boolean) {
        val previous = activityBinding
        activityBinding = null
        val failures = ExceptionCollector()
        failures.attempt { scanner?.detachActivity() }
        failures.attempt { previous?.removeRequestPermissionsResultListener(permissionResultListener) }
        failures.attempt { if (isFinal) permissionGateway.detachFinal() else permissionGateway.detachForConfigChange() }
        if (isFinal) failures.attempt { scanner?.dispose() }
        failures.throwIfFailed()
    }

    /** Drops channel ownership before any external cleanup callback. */
    internal fun dispose() {
        if (isDisposed) return
        val connected = channel
        channel = null
        val failures = ExceptionCollector()
        failures.attempt { detach(isFinal = true) }
        views.clear()
        failures.attempt { commandScope.cancel() }
        failures.attempt { connected?.setMethodCallHandler(null) }
        failures.throwIfFailed()
    }

    /** Capture selects a view directly; controls resolve only the current scanner. */
    override fun onMethodCall(call: MethodCall, result: Result) {
        if (isDisposed) return
        when (call.method) {
            PluginConstants.captureCameraMethod -> captureCamera(call, result)
            PluginConstants.releaseCameraMethod -> releaseCamera(result)

            PluginConstants.setZoomRatioMethod -> SetZoomRatioCommand(::currentScanner, commandScope).execute(call, result)
            PluginConstants.toggleFlashMethod -> ToggleFlashCommand(::currentScanner, commandScope).execute(call, result)
            PluginConstants.startScanMethod -> StartScanCommand(::currentScanner).execute(call, result)
            PluginConstants.cancelScanMethod -> CancelScanCommand(::currentScanner).execute(call, result)
            PluginConstants.setScanDelayMethod -> SetScanDelayCommand(::currentScanner).execute(call, result)
            PluginConstants.setCropAreaMethod -> SetCropAreaCommand(::currentScanner).execute(call, result)

            else -> result.notImplemented()
        }
    }

    private fun releaseCamera(result: Result) {
        try {
            currentScanner()?.releaseCamera()
            result.success(true)
        } catch (error: Exception) {
            reportScannerError(result, error)
        }
    }

    /** Selects before the first await so a later capture or release can supersede this work. */
    private fun captureCamera(call: MethodCall, result: Result) {
        commandScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                val arguments = call.arguments.requireMap()
                val viewId = arguments.requireInt(PluginConstants.viewIdArgument)
                if (viewId < 0) throw PluginError.InvalidArguments
                val configuration = ScannerConfiguration.from(arguments["configuration"])
                val lifecycle = activityBinding?.activityLifecycle
                val view = views[viewId]?.takeUnless { it.isDisposed }
                if (lifecycle == null || view == null) {
                    // The addressed view or Activity is gone; this capture is superseded work.
                    result.success(true)
                    return@launch
                }

                val device = scanner ?: scannerFactory(view.context, ::scannerReleased, ::emitScanResult)
                try {
                    if (scanner == null) {
                        scanner = device
                        device.attachActivity(lifecycle)
                    }
                    device.select(view)
                } catch (error: Exception) {
                    if (scanner === device) scanner = null
                    ExceptionCollector(error).attempt { device.dispose(error) }
                    throw error
                }
                device.capture(configuration) {
                    permissionGateway.requestCameraPermission()
                }
                result.success(true)
            } catch (error: CancellationException) {
                reportScannerError(result, PluginError.CameraSessionDisposed)
                throw error
            } catch (error: Exception) {
                reportScannerError(result, error)
            }
        }
    }

    /** View registration never allocates a camera, analyzer or a per-view native controller. */
    private fun createView(context: Context, viewId: Int): ScannerView {
        check(!isDisposed) { "Cannot create a view in a disposed scanner plugin" }
        val connected = channel
        lateinit var view: ScannerView
        view = ScannerView(
            context, viewId,
            { delay, x, y -> if (channel === connected && view.isPreviewReady()) currentScanner()?.focus(delay, x, y) },
            { if (channel === connected) unregister(view) })
        views[viewId] = view
        return view
    }

    /** Remove only this instance; a late disposal must not unregister its replacement. */
    private fun unregister(view: ScannerView) {
        if (views[view.viewId] !== view) return
        views.remove(view.viewId)
        scanner?.takeIf { it.viewId == view.viewId }?.releaseCamera()
    }

    /** A delayed disposal cannot clear a replacement scanner. */
    private fun scannerReleased(device: Scanner, cause: Throwable) {
        if (scanner === device) scanner = null
    }

    /** Transport adds the View's routing address to a main-thread recognition result. */
    private fun emitScanResult(viewId: Int, result: Barcode) {
        channel?.invokeMethod(
            PluginConstants.scanResultMethod,
            mapOf(
                PluginConstants.viewIdArgument to viewId,
                PluginConstants.barcodeArgument to result.toMap()
            ),
        )
    }

    private companion object {
        /** Rollback covers all adapters allocated before hardware construction succeeds. */
        fun createScanner(
            context: Context, handler: Handler,
            released: (Scanner, Throwable) -> Unit, result: (Int, Barcode) -> Unit
        ): Scanner {
            val scope = Scanner.createScope()
            var camera: XCamera? = null
            var analyzer: MlkitImageBarcodeAnalyzer? = null
            try {
                val createdCamera = XCamera(context).also { camera = it }
                val createdAnalyzer = MlkitImageBarcodeAnalyzer().also { analyzer = it }
                return Scanner(
                    createdCamera,
                    createdAnalyzer,
                    handler,
                    released,
                    result,
                    scope
                )
            } catch (error: Exception) {
                val failures = ExceptionCollector(error)
                failures.attempt { scope.cancel() }
                failures.attempt { camera?.dispose() }
                failures.attempt { analyzer?.dispose() }
                throw error
            }
        }
    }
}
