package com.dns_technologies.mlkit_scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.util.Log
import android.view.WindowManager
import androidx.annotation.NonNull
import com.otaliastudios.cameraview.CameraView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.dns_technologies.mlkit_scanner.analyzer.AnalyzerCreator
import com.dns_technologies.mlkit_scanner.analyzer.CameraImageAnalyzer
import com.dns_technologies.mlkit_scanner.analyzer.TAG
import com.dns_technologies.mlkit_scanner.models.*

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlin.Exception

class PermissionsConstants {
  companion object {
    const val REQUEST_CODE_PERMISSIONS = 10
    val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
  }
}

/**
 * Android plugin for working with ML Kit scanner
 *
 * The [CameraView] library is used for simplification of Camera1
 * [CameraLifecycle] is used for [ScannerCamera] lifecycle managing
 * Plugin inherits [ActivityAware] for checking camera user permissions
 */
class MlkitScannerPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, LifecycleObserver {
  private lateinit var channel : MethodChannel
  private lateinit var binding: ActivityPluginBinding
  private lateinit var cameraView: CameraView
  private var camera: ScannerCamera? = null
  private var cameraLifecycle: CameraLifecycle? = null
  // The field contains the Result passed during initialization. It is necessary for throwing errors
  // into flutter that can occur asynchronously during initialization. For example,
  // camera permission error that can be handled in listenPermissionResult method
  private var initialMethodResult: Result? = null
  private var cameraImagePreparer = MlKitAnalysingImagePreparer()
  private var analyzer: CameraImageAnalyzer? = null
  private var scannerOverlay: ScannerOverlay? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, PluginConstants.channelName)
    channel.setMethodCallHandler(this)
    flutterPluginBinding
            .platformViewRegistry
            .registerViewFactory(PluginConstants.cameraPlatformViewName, 
              CameraViewFactory {
                cameraView = it
              })
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      PluginConstants.initCameraMethod -> invokeInit(result)
      PluginConstants.resumeCameraMethod -> resumeCamera(result)
      PluginConstants.pauseCameraMethod -> pauseCamera(result)
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

  @Suppress("UNUSED_PARAMETER")
  private fun listenPermissionResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
    if (requestCode == PermissionsConstants.REQUEST_CODE_PERMISSIONS) {
      if (allPermissionsGranted()) {
        initCamera()
      } else {
        initialMethodResult?.error(PluginError.AuthorizationCameraError.errorCode,
                "The app does not have camera permission",
                null)
        initialMethodResult = null
      }
    }
    return true
  }

  private fun invokeInit(result: Result) {
    initialMethodResult = result
    if (allPermissionsGranted()) {
      initCamera()
    } else {
      requestAllPermission()
    }
  }

  private fun resumeCamera(result: Result) {
    cameraLifecycle!!.resume()
    result.success(true)
  }

  private fun pauseCamera(result: Result) {
    cameraLifecycle!!.pause()
    result.success(true)
  }

  private fun invokeToggleFlash(result: Result) {
    if (checkCameraActiveStatus(result,
                    "You need to invoke the \'initCameraPreview\' method before using flash")) {
      try {
        camera?.toggleFlashLight()
        result.success(true)
      } catch (e: HasNoFlashUnitException) {
        result.error(PluginError.DeviceHasNotFlash.errorCode,
                "Device has no flash",
                null)
      }
    }
  }

  private fun invokeStartScan(call: MethodCall, result: Result) {
    val options = AnalyzeOptions.fromMap(call.arguments as Map<String, Any?>)
    if (cameraLifecycle == null) {
      return result.error(
        PluginError.CameraIsNotInitialized.errorCode,
        "You need to invoke \'initCameraPreview\' method before start scan",
        null
      )
    }
    analyzer?.resumeScan(options.periodMs)
    if (analyzer?.isDisposed == true || analyzer?.type != options.recognizeType) {
      analyzer = AnalyzerCreator.create(options.recognizeType)
      analyzer?.init(options.periodMs, this::onScan, cameraImagePreparer::prepare)
    }
    camera?.attachAnalyser(analyzer!!)
    scannerOverlay?.isActive = true
    result.success(true)
  }

  private fun invokeCancelScan(result: Result) {
    analyzer?.pauseScan()
    scannerOverlay?.isActive = false
    result.success(true)
  }

  private fun invokeSetScanDelay(call: MethodCall, result: Result) {
    val delay = call.arguments
    if (delay !is Number) {
      result.error(PluginError.InvalidArguments.errorCode,
              "Invalid argument passed, Number type is expected",
              null)
      return
    }
    analyzer?.updatePeriod(delay as Int)
    result.success(true)
  }

  private fun invokeDispose(result: Result) {
    cameraLifecycle?.dispose()
    cameraLifecycle = null
    scannerOverlay = null
    analyzer?.dispose()
    result.success(true)
  }

  private fun initCamera() {
    cameraLifecycle = CameraLifecycle()
    createScannerCamera()
    cameraLifecycle!!.resume()
    cameraView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      val cropRect = scannerOverlay?.cropRect
      if (cropRect != null) {
        updateCropOptions(cropRect)
      }
    }
  }

  private fun createScannerCamera() {
    if (camera == null || camera?.isActive() != true) {
      camera = CameraViewScannerCamera(cameraLifecycle!!, cameraView)
      camera?.startCamera(this::onInitSuccess, this::onInitError)
    }
  }

  private fun onInitSuccess() {
    if (analyzer != null) {
      camera?.attachAnalyser(analyzer!!)
    }
    initialMethodResult?.success(true)
    initialMethodResult = null
  }

  private fun onInitError(e: Exception) {
    Log.e(TAG, e.toString())
    initialMethodResult?.error(PluginError.InitCameraError.errorCode,
            "Internal camera initialisation error",
            e.message)
    initialMethodResult = null
  }

  private fun invokeSetZoom(call: MethodCall, result: Result) {
    if (checkCameraActiveStatus(result, "You need to invoke the 'initCameraPreview' method before using zoom")) {
      val value = call.arguments
      if (value !is Double) {
        result.error(PluginError.InvalidArguments.errorCode,
          "Invalid argument passed, Double type is expected",
          null)
        return
      }
      try {
        camera?.setZoom(value.toFloat())
        result.success(true)
      } catch (e: ZoomNotSupportedException) {
        result.error(PluginError.DeviceHasNotZoom.errorCode,
          "Zoom is not supported on this device",
          null)
      }
    }
  }

  private fun invokeSetCropArea(call: MethodCall, result: Result) {
    val rect = RecognizeVisorCropRect.fromMap(call.arguments as Map<String, Any?>)
    updateCropOptions(rect)
    if (scannerOverlay != null) {
      scannerOverlay?.cropRect = rect
    } else {
      scannerOverlay = ScannerOverlay(rect, cameraView.context)
      cameraView.addOverlay(scannerOverlay!!)
    }
    result.success(true)
  }

  private fun checkCameraActiveStatus(result: Result, errorMsg: String): Boolean {
    if (camera?.isActive() != true) {
      result.error(PluginError.CameraIsNotInitialized.errorCode, errorMsg, null)
      return false
    }
    return true
  }

  private fun allPermissionsGranted() = PermissionsConstants.REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(binding.activity.baseContext, it) == PackageManager.PERMISSION_GRANTED
  }

  private fun requestAllPermission() = ActivityCompat.requestPermissions(
          binding.activity,
          PermissionsConstants.REQUIRED_PERMISSIONS,
          PermissionsConstants.REQUEST_CODE_PERMISSIONS)

  private fun updateCropOptions(cropRect: RecognizeVisorCropRect) {
    val screenSize = getDisplaySize()
    val scaleX = cameraView.measuredWidth.toDouble() / screenSize.x
    val scaleY = cameraView.measuredHeight.toDouble() / screenSize.y
    camera?.changeFocusCenter(cropRect.centerOffsetX.toFloat(), cropRect.centerOffsetY.toFloat())
    cameraImagePreparer.visorRectFormer.updateWidgetScales(scaleX, scaleY)
    cameraImagePreparer.visorRectFormer.recognizeCropRect = cropRect
  }

  private fun getDisplaySize(): Point {
    return Point().apply {
      val display = (binding.activity.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
      display.getSize(this)
    }
  }

  private fun onScan(barcode: Any) = channel.invokeMethod(PluginConstants.scanResultMethod, barcode as String)

  @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
  private fun onResume() {
    if (cameraLifecycle != null) {
      cameraLifecycle!!.resume()
    }
  }

  @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
  private fun onPause() {
    if (camera?.isActive() == true) {
      cameraLifecycle!!.pause()
    }
  }
}