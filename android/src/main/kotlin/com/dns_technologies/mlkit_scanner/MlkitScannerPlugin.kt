package com.dns_technologies.mlkit_scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.commands.*
import com.dns_technologies.mlkit_scanner.commands.base.reportScannerError
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.*
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.mlkit.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.x.XCamera
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.utils.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Transport and native lifecycle. Flutter owns widget demand and the idle timer. */
class MlkitScannerPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    /** Channel used for command replies and preview/recognition events while attached. */
    private var channel: MethodChannel? = null
    /** Application context borrowed from the current engine attachment. */
    private var context: Context? = null
    /** Engine registry used to allocate the shared preview texture. */
    private var textures: TextureRegistry? = null
    /** Current camera and recognition coordinator, created on the first capture. */
    private var scanner: Scanner? = null
    /** Scanner retained until its owned resources finish asynchronous disposal. */
    private var disposingScanner: Scanner? = null
    /** Registered Flutter widgets keyed by their logical view identifiers. */
    private val consumers = mutableMapOf<Int, ScannerConsumer>()
    /** Live preview event endpoints independent of capture ownership. */
    private val previewSubscriptions = mutableMapOf<String, ResultEndpoint>()
    /** Latest texture description returned to newly registered widgets. */
    private var preview: Map<String, Any>? = null
    /** Exclusive capture lease whose identity rejects stale commands and results. */
    private var selected: CaptureLease? = null
    /** Permission requests that survive Activity configuration changes. */
    private val permissions = PermissionGateway()
    /** Current Activity attachment that owns the permission listener. */
    private var activityBinding: ActivityPluginBinding? = null
    /** Routes Android permission callbacks to the shared permission gateway. */
    private val permissionListener =
        PluginRegistry.RequestPermissionsResultListener(permissions::onPermissionResult)
    /** Actual Activity lifecycle supplied by Flutter's lifecycle adapter. */
    private val ActivityPluginBinding.activityLifecycle: Lifecycle
        get() = FlutterLifecycleAdapter.getActivityLifecycle(this)

    /** Main-thread dispatcher for result delivery and deferred disposal replies. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Registers the command channel and borrows engine resources. */
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        textures = binding.textureRegistry
        channel =
            MethodChannel(binding.binaryMessenger, PluginConstants.channelName).also {
                it.setMethodCallHandler(this)
            }
    }

    /** Releases native resources and clears references to the detached engine. */
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        try {
            disposeResources()
            detach(true)
        } finally {
            consumers.clear()
            previewSubscriptions.clear()
            channel?.setMethodCallHandler(null)
            channel = null
            context = null
            textures = null
        }
    }

    /** Attaches permissions and any retained scanner to the current Activity. */
    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attach(binding)

    /** Restores Activity-dependent resources after a configuration change. */
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        attach(binding)

    /** Releases the old Activity while preserving pending permission requests. */
    override fun onDetachedFromActivityForConfigChanges() = detach(false)

    /** Ends Activity ownership and releases scanner resources. */
    override fun onDetachedFromActivity() = detach(true)

    /** Connects permission callbacks and the camera to the attached Activity. */
    private fun attach(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(permissionListener)
        permissions.attach(binding.activity)
        scanner?.attachActivity(binding.activityLifecycle)
    }

    /** Revokes capture ownership and applies temporary or terminal Activity cleanup. */
    private fun detach(final: Boolean) {
        val binding = activityBinding
        activityBinding = null
        closeSelected()
        scanner?.detachActivity()
        binding?.removeRequestPermissionsResultListener(permissionListener)
        if (final) {
            permissions.detachFinal()
            disposeResources()
        } else permissions.detachForConfigChange()
    }

    /** Handles registrations and routes capture commands through their owning lease. */
    override fun onMethodCall(call: MethodCall, result: Result) {
        if (channel == null) {
            reportScannerError(result, PluginError.CameraSessionDisposed)
            return
        }
        try {
            when (call.method) {
                "registerScanner" -> {
                    val id = call.arguments.requireMap().requireInt("viewId")
                    if (id < 0) throw PluginError.InvalidArguments
                    consumers.getOrPut(id) { ScannerConsumer(id) }
                    result.success(preview)
                }
                "unregisterScanner" -> {
                    val id = call.arguments.requireMap().requireInt("viewId")
                    if (selected?.consumer?.viewId == id) closeSelected()
                    consumers.remove(id)
                    result.success(null)
                }
                "subscribePreview" -> {
                    val endpoint = ResultEndpoint()
                    previewSubscriptions[endpoint.id] = endpoint
                    result.success(mapOf("subscriptionId" to endpoint.id, "description" to preview))
                }
                "unsubscribePreview" -> {
                    previewSubscriptions
                        .remove(call.arguments.requireMap()["subscriptionId"] as? String)
                        ?.close()
                    result.success(null)
                }
                "openCapture" -> {
                    val id = call.arguments.requireMap().requireInt("viewId")
                    val consumer = consumers[id] ?: throw PluginError.InvalidArguments
                    closeSelected()
                    val lease = CaptureLease(consumer)
                    selected = lease
                    result.success(lease.id)
                }
                "closeCapture" -> {
                    if (selected?.id == call.arguments.requireMap()["captureId"]) closeSelected()
                    result.success(null)
                }
                "disposeScanner" -> disposeScanner(result)
                else -> executeScoped(call, result)
            }
        } catch (error: Exception) {
            reportScannerError(result, error)
        }
    }

    /** Validates capture ownership before running controls with an at-most-once reply. */
    private fun executeScoped(call: MethodCall, result: Result) {
        val values = call.arguments.requireMap()
        val lease =
            selected?.takeIf { !it.closed && it.id == values["captureId"] }
                ?: throw PluginError.CameraSessionDisposed
        val reply = lease.reply(result)
        val current = {
            if (selected !== lease || lease.closed) throw PluginError.CameraSessionDisposed
            scanner ?: throw PluginError.CameraIsNotInitialized
        }
        try {
            when (call.method) {
                PluginConstants.resumeCameraMethod -> resumeCapture(lease, values, reply)
                PluginConstants.pauseCameraMethod -> {
                    scanner?.pauseCamera()
                    reply.success(null)
                    closeSelected()
                }
                "updatePreviewGeometry" -> {
                    lease.consumer.updateGeometry(values)
                    current().updateGeometry()
                    reply.success(null)
                }
                "focus" -> {
                    val locked = values.requireBoolean("locked")
                    current().focusCropCenter(if (locked) 0 else 3000)
                    reply.success(null)
                }
                "subscribeScan" -> {
                    lease.subscription?.close()
                    current().pauseScan()
                    val endpoint = ResultEndpoint()
                    lease.subscription = endpoint
                    reply.success(endpoint.id)
                }
                PluginConstants.startScanMethod -> {
                    val endpoint =
                        lease.subscription?.takeIf {
                            !it.closed && it.id == values["subscriptionId"]
                        } ?: throw PluginError.InvalidArguments
                    endpoint.enabled = true
                    StartScanCommand(current).execute(call, reply)
                }
                PluginConstants.cancelScanMethod -> {
                    lease.subscription?.close()
                    lease.subscription = null
                    CancelScanCommand(current).execute(call, reply)
                }
                PluginConstants.setZoomRatioMethod ->
                    SetZoomRatioCommand(current, lease.scope).execute(call, reply)
                "updateCameraSettings" ->
                    UpdateCameraSettingsCommand(current, lease.scope).execute(call, reply)
                PluginConstants.toggleFlashMethod ->
                    ToggleFlashCommand(current, lease.scope).execute(call, reply)
                PluginConstants.setScanDelayMethod ->
                    SetScanDelayCommand(current).execute(call, reply)
                PluginConstants.setCropAreaMethod ->
                    SetCropAreaCommand(current).execute(call, reply)
                else -> reply.notImplemented()
            }
        } catch (error: Exception) {
            reportScannerError(reply, error)
        }
    }

    /**
     * Completes disposal after the camera has released all preview resources.
     */
    private fun disposeScanner(result: Result) {
        val device = scanner ?: disposingScanner
        val cleanupError = runCatching { disposeResources() }.exceptionOrNull()
        if (device == null) {
            if (cleanupError == null) result.success(null)
            else
                reportScannerError(
                    result,
                    cleanupError as? Exception ?: RuntimeException(cleanupError),
                )
        } else
            device.disposal.invokeOnCompletion { error ->
                val complete = Runnable {
                    val failure = cleanupError ?: error
                    if (failure == null) result.success(null)
                    else
                        reportScannerError(
                            result,
                            failure as? Exception ?: RuntimeException(failure),
                        )
                }
                if (Looper.myLooper() == Looper.getMainLooper()) complete.run()
                else mainHandler.post(complete)
            }
    }

    /**
     * Validates the capture snapshot and resumes camera work within the selected lease lifetime.
     */
    private fun resumeCapture(lease: CaptureLease, values: Map<*, *>, reply: PendingReply) {
        val configuration = ScannerConfiguration.from(values["configuration"])
        lease.consumer.updateGeometry(values["geometry"])
        lease.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (!permissions.requestCameraPermission())
                    throw PluginError.AuthorizationCameraError
                currentCoroutineContext().ensureActive()
                if (selected !== lease) throw PluginError.CameraSessionDisposed
                disposingScanner?.disposal?.await()
                currentCoroutineContext().ensureActive()
                if (selected !== lease) throw PluginError.CameraSessionDisposed
                val lifecycle =
                    activityBinding?.activityLifecycle ?: throw PluginError.CameraSessionDisposed
                val device =
                    scanner
                        ?: createScanner().also {
                            scanner = it
                            it.attachActivity(lifecycle)
                        }
                device.select(lease.consumer)
                device.capture(configuration)
                reply.success(null)
            } catch (error: Exception) {
                reportScannerError(
                    reply,
                    if (error is CancellationException) PluginError.CameraSessionDisposed else error,
                )
            }
        }
    }

    /** Revokes the current lease before releasing its scanner selection. */
    private fun closeSelected() {
        val old = selected
        selected = null
        old?.close()
        scanner?.releaseCamera()
    }

    /** Attempts independent scanner cleanup steps and reports the first failure. */
    private fun disposeResources() {
        val failures = ExceptionCollector()
        failures.attempt(::closeSelected)
        val old = scanner
        scanner = null
        if (old != null) trackDisposal(old)
        failures.attempt { old?.dispose() }
        publishPreview(null)
        failures.throwIfFailed()
    }

    /** Creates the shared texture camera and recognition coordinator for this engine. */
    private fun createScanner(): Scanner = Scanner.create(
        cameraFactory = {
            XCamera(requireNotNull(context), requireNotNull(textures).createSurfaceProducer())
        },
        analyzerFactory = ::MlkitImageBarcodeAnalyzer,
        mainHandler = mainHandler,
        onReleased = { device, _ ->
            if (scanner === device) {
                scanner = null
                trackDisposal(device)
                publishPreview(null)
            }
        },
        onResult = ::emitResult,
        onPreviewChanged = { device, description ->
            if (scanner === device) publishPreview(description)
        },
    )

    /** Keeps concurrent disposal and replacement capture waiting on the same scanner. */
    private fun trackDisposal(device: Scanner) {
        disposingScanner = device
        device.disposal.invokeOnCompletion {
            val clear = Runnable {
                if (disposingScanner === device) disposingScanner = null
            }
            if (Looper.myLooper() == Looper.getMainLooper()) clear.run()
            else mainHandler.post(clear)
        }
    }

    /** Stores the latest texture state and delivers it to live preview subscribers. */
    private fun publishPreview(description: Map<String, Any>?) {
        preview = description
        for (subscription in previewSubscriptions.values.toList()) {
            if (!subscription.closed)
                channel?.invokeMethod(
                    "onPreviewState",
                    mapOf("subscriptionId" to subscription.id, "description" to description),
                )
        }
    }

    /** Delivers a barcode only to the active capture's enabled event endpoint. */
    private fun emitResult(viewId: Int, barcode: Barcode) {
        val lease = selected?.takeIf { it.consumer.viewId == viewId && !it.closed } ?: return
        val endpoint = lease.subscription?.takeIf { it.enabled && !it.closed } ?: return
        channel?.invokeMethod(
            PluginConstants.scanResultMethod,
            mapOf(
                "viewId" to viewId,
                "captureId" to lease.id,
                "subscriptionId" to endpoint.id,
                "barcode" to barcode.toMap(),
            ),
        )
    }
}
