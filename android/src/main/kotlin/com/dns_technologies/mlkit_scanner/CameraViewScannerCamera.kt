package com.dns_technologies.mlkit_scanner

import android.util.Size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.dns_technologies.mlkit_scanner.analyzer.CameraImageAnalyzer
import com.dns_technologies.mlkit_scanner.analyzer.NV21AnalysingImage
import com.dns_technologies.mlkit_scanner.models.HasNoFlashUnitException
import com.dns_technologies.mlkit_scanner.models.ZoomNotSupportedException
import com.otaliastudios.cameraview.CameraException
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraOptions
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.controls.Flash
import com.otaliastudios.cameraview.frame.Frame
import com.otaliastudios.cameraview.size.Size as CameraViewSize

/**
 * [ScannerCamera] realisation based on [CameraView]
 *
 * The class implements the [LifecycleObserver] and [CameraListener] interfaces,
 * which is necessary for the correct processing of the [startCamera] method
 *
 * @see [CameraView](https://github.com/natario1/CameraView)
 */
class CameraViewScannerCamera(
    private val lifecycleOwner: LifecycleOwner,
    private val cameraView: CameraView,
    initialZoom: Double?,
) : ScannerCamera,
    LifecycleObserver,
    CameraListener() {
    private var analyzer: CameraImageAnalyzer? = null
    private lateinit var onInitSuccess: OnInit
    private lateinit var onInitError: OnError
    private lateinit var cameraOptions: CameraOptions
    private val hasSupportedFlash: Boolean by lazy {
        cameraOptions.supportedFlash.containsAll(arrayListOf(Flash.OFF, Flash.TORCH))
    }
    private var focusCenter: Pair<Float, Float> = Pair(0.0F, 0.0F)

    init {
        cameraView.useDeviceOrientation = false
        if (initialZoom != null) {
            cameraView.zoom = initialZoom.toFloat()
        }
        cameraView.addCameraListener(this)
        cameraView.setLifecycleOwner(lifecycleOwner)
        cameraView.setPreviewStreamSize {
            it.apply {
                clear()
                add(CameraViewSize(720, 1280))
            }
        }
    }

    /**
     * There is no logic to initialize the camera in this method actually.
     * The initialization logic is calling in [CameraView] in response to the
     * [Lifecycle.Event.ON_RESUME] event. Successful / unsuccessful camera launch is handled in
     * [onCameraOpened] / [onCameraError] callbacks, respectively.
     */
    override fun startCamera(onInit: OnInit, onError: OnError) {
        onInitSuccess = onInit
        onInitError = onError
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun isActive(): Boolean = cameraView.isOpened

    /**
     * Shifts the focus from the center
     */
    override fun changeFocusCenter(widthOffset: Float, heightOffset: Float) {
        // save focus center position for case when CenterFocusView not added in tree views.
        focusCenter = Pair(widthOffset, heightOffset)

        cameraView.changeFocusCenter(widthOffset, heightOffset)
        cameraView.removeCameraListener(this)
    }

    override fun toggleFlashLight() {
        if (!hasSupportedFlash) throw HasNoFlashUnitException()
        val currentFlash = cameraView.flash
        cameraView.flash = if (currentFlash == Flash.OFF) Flash.TORCH else Flash.OFF
    }

    override fun attachAnalyser(analyzer: CameraImageAnalyzer) {
        clearAnalyzer()
        this.analyzer = analyzer
        cameraView.addFrameProcessor(this::analyzeFrame)
    }

    override fun clearAnalyzer() {
        if (analyzer != null) {
            this.analyzer = null
            cameraView.clearFrameProcessors()
        }
    }

    override fun onCameraOpened(options: CameraOptions) {
        cameraOptions = options
        onInitSuccess.invoke()
        cameraView.useCenterFocus(focusCenter)
        cameraView.removeCameraListener(this)
        super.onCameraOpened(options)
    }

    override fun onCameraError(exception: CameraException) {
        onInitError.invoke(exception)
        cameraView.removeCameraListener(this)
        super.onCameraError(exception)
    }

    private fun analyzeFrame(frame: Frame) {
        if (analyzer != null) {
            with(frame) {
                analyzer!!.analyze(
                    NV21AnalysingImage(
                        getData(),
                        Size(size.width, size.height),
                        format,
                        rotationToUser
                    )
                )
            }
        }
    }

    override fun setZoom(value: Float) {
        if (cameraOptions.isZoomSupported) {
            cameraView.zoom = value
        } else {
            throw ZoomNotSupportedException()
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    private fun onStop() {
        cameraView.removeCameraListener(this)
    }
}