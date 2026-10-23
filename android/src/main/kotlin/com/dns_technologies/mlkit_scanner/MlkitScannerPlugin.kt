package com.dns_technologies.mlkit_scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.commands.*
import com.dns_technologies.mlkit_scanner.commands.base.BaseScannerCommand
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.*
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.mlkit.MlkitImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewDescription
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewState
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Routes scanner commands and events and manages engine and Activity attachment. */
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
    /** Preview subscription identifiers independent of capture ownership. */
    private val previewSubscriptions = mutableSetOf<String>()
    /** Latest texture description returned to new preview subscriptions. */
    private var preview: CameraPreviewDescription? = null
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

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        textures = binding.textureRegistry
        channel =
            MethodChannel(binding.binaryMessenger, PluginConstants.channelName).also {
                it.setMethodCallHandler(this)
            }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        try {
            disposeResources()
            detach(true)
        } finally {
            previewSubscriptions.clear()
            channel?.setMethodCallHandler(null)
            channel = null
            context = null
            textures = null
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attach(binding)

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        attach(binding)

    override fun onDetachedFromActivityForConfigChanges() = detach(false)

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

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (channel == null) {
            BaseScannerCommand.reportScannerError(result, PluginError.CameraSessionDisposed)
            return
        }
        try {
            when (call.method) {
                "subscribePreview" -> {
                    val subscriptionId = UUID.randomUUID().toString()
                    previewSubscriptions += subscriptionId
                    result.success(mapOf("subscriptionId" to subscriptionId, "description" to preview?.toMap()))
                }
                "unsubscribePreview" -> {
                    previewSubscriptions.remove(call.arguments.requireMap()["subscriptionId"] as? String)
                    result.success(null)
                }
                "capture" -> capture(call.arguments.requireMap(), result)
                "release" -> {
                    if (selected?.id == call.arguments.requireMap()["captureId"]) closeSelected()
                    result.success(null)
                }
                "disposeScanner" -> disposeScanner(result)
                else -> executeScoped(call, result)
            }
        } catch (error: Exception) {
            BaseScannerCommand.reportScannerError(result, error)
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
                "pauseCameraMethod" -> {
                    scanner?.pauseCamera()
                    reply.success(null)
                    closeSelected()
                }
                "updatePreviewGeometry" -> {
                    lease.consumer.updateGeometry(values.requireSize())
                    current().updateGeometry()
                    reply.success(null)
                }
                "focus" -> {
                    val locked = values.requireBoolean("locked")
                    current().focusCropCenter(if (locked) 0 else 3000)
                    reply.success(null)
                }
                "startScan" -> {
                    val id = values["subscriptionId"] as? String ?: throw PluginError.InvalidArguments
                    val delay = values.requireInt("delay")
                    if (delay < 0) throw PluginError.InvalidArguments
                    val subscription = lease.startScan(id)
                    current().pauseScan()
                    subscription.enabled = true
                    current().startScan(delay)
                    reply.success(null)
                }
                "cancelScan" -> {
                    lease.scanLease?.close()
                    lease.scanLease = null
                    CancelScanCommand(current).execute(call, reply)
                }
                "setZoomRatio" ->
                    SetZoomRatioCommand(current, lease.scope).execute(call, reply)
                "updateCameraSettings" ->
                    UpdateCameraSettingsCommand(current, lease.scope).execute(call, reply)
                "toggleFlash" ->
                    ToggleFlashCommand(current, lease.scope).execute(call, reply)
                "setScanDelay" ->
                    SetScanDelayCommand(current).execute(call, reply)
                "setCropAreaMethod" ->
                    SetCropAreaCommand(current).execute(call, reply)
                else -> reply.notImplemented()
            }
        } catch (error: Exception) {
            BaseScannerCommand.reportScannerError(reply, error)
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
                BaseScannerCommand.reportScannerError(
                    result,
                    cleanupError as? Exception ?: RuntimeException(cleanupError),
                )
        } else
            device.disposal.invokeOnCompletion { error ->
                val complete = Runnable {
                    val failure = cleanupError ?: error
                    if (failure == null) result.success(null)
                    else
                        BaseScannerCommand.reportScannerError(
                            result,
                            failure as? Exception ?: RuntimeException(failure),
                        )
                }
                if (Looper.myLooper() == Looper.getMainLooper()) complete.run()
                else mainHandler.post(complete)
            }
    }

    /** Acquires camera ownership with a complete camera and recognition snapshot. */
    private fun capture(values: Map<*, *>, result: Result) {
        val viewId = values.requireInt("viewId")
        val id = (values["captureId"] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw PluginError.InvalidArguments
        if (viewId < 0) throw PluginError.InvalidArguments
        val configuration = ScannerConfiguration.from(values["configuration"])
        val geometry = values.requireMap("geometry").requireSize()
        val delay = values.requireInt("delay")
        if (delay < 0) throw PluginError.InvalidArguments
        val scanId = values["subscriptionId"]
        if (scanId != null && (scanId !is String || scanId.isEmpty()))
            throw PluginError.InvalidArguments
        val lease = selected?.takeIf { it.id == id } ?: run {
            closeSelected()
            CaptureLease(ScannerConsumer(viewId), id).also { selected = it }
        }
        if (lease.consumer.viewId != viewId) throw PluginError.InvalidArguments
        lease.consumer.updateGeometry(geometry)
        lease.scanLease?.close()
        lease.scanLease = null
        val subscription = (scanId as? String)?.let(lease::startScan)
        val reply = lease.reply(result)
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
                currentCoroutineContext().ensureActive()
                if (selected !== lease) throw PluginError.CameraSessionDisposed
                if (subscription != null && lease.scanLease === subscription && !subscription.closed) {
                    subscription.enabled = true
                    device.startScan(delay)
                }
                reply.success(null)
            } catch (error: Exception) {
                BaseScannerCommand.reportScannerError(
                    reply,
                    if (error is CancellationException) PluginError.CameraSessionDisposed else error,
                )
            }
        }
    }

    /** Releases camera ownership and cancels outstanding capture work. */
    private fun closeSelected() {
        val old = selected ?: return
        selected = null
        old.close()
        scanner?.releaseCapture()
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
    private fun publishPreview(description: CameraPreviewDescription?) {
        preview = description
        val payload = description?.toMap()
        for (subscriptionId in previewSubscriptions.toList()) {
            if (subscriptionId in previewSubscriptions)
                channel?.invokeMethod(
                    "onPreviewState",
                    mapOf("subscriptionId" to subscriptionId, "description" to payload),
                )
        }
    }

    /** Encodes preview metadata only where it crosses the platform channel. */
    private fun CameraPreviewDescription.toMap(): Map<String, Any> = mapOf(
        "textureId" to textureId,
        "width" to width,
        "height" to height,
        "rotationDegrees" to rotationDegrees,
        "mirrored" to mirrored,
        "state" to when (state) {
            CameraPreviewState.Starting -> "starting"
            CameraPreviewState.Streaming -> "streaming"
            CameraPreviewState.Paused -> "paused"
        },
        "cropLeft" to cropRect.left,
        "cropTop" to cropRect.top,
        "cropWidth" to cropRect.width,
        "cropHeight" to cropRect.height,
    )

    /** Delivers a barcode only to the active capture's enabled scan subscription. */
    private fun emitResult(viewId: Int, barcode: Barcode) {
        val lease = selected?.takeIf { it.consumer.viewId == viewId && !it.closed } ?: return
        val subscription = lease.scanLease?.takeIf { it.enabled && !it.closed } ?: return
        channel?.invokeMethod(
            "onScanResult",
            mapOf(
                "viewId" to viewId,
                "captureId" to lease.id,
                "subscriptionId" to subscription.id,
                "barcode" to barcode.toMap(),
            ),
        )
    }
}
