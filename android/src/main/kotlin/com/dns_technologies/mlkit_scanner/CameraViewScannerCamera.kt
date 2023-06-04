package com.dns_technologies.mlkit_scanner

import android.media.Image
import android.util.Size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.dns_technologies.mlkit_scanner.analyzer.CameraImageAnalyzer
import com.dns_technologies.mlkit_scanner.analyzer.NV21MlKitImageBuilder
import com.dns_technologies.mlkit_scanner.analyzer.YUV420888MlKitImageBuilder
import com.dns_technologies.mlkit_scanner.configs.ImageProcessingConfig
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
) : ScannerCamera,
    LifecycleObserver,
    CameraListener() {
    private var analyzer: CameraImageAnalyzer? = null
    private lateinit var onInitSuccess: OnInit
    private lateinit var onInitError: OnError
    private var cameraOptions: CameraOptions? = null
    private val hasSupportedFlash
        get() = cameraOptions?.supportedFlash?.containsAll(
            arrayListOf(
                Flash.OFF,
                Flash.TORCH
            )
        ) ?: false
    private var focusCenter: Pair<Float, Float> = Pair(0.0F, 0.0F)

    init {
        with(cameraView) {
            useDeviceOrientation = false
            addCameraListener(this@CameraViewScannerCamera)
            setLifecycleOwner(lifecycleOwner)
            setPreviewStreamSize {
                it.apply {
                    clear()
                    add(with(ImageProcessingConfig) {
                        CameraViewSize(
                            resolution.first,
                            resolution.second
                        )
                    })
                }
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
        cameraView.useCenterFocus(focusCenter)
        onInitSuccess.invoke()
        cameraView.removeCameraListener(this)
        super.onCameraOpened(options)
    }

    override fun onCameraError(exception: CameraException) {
        onInitError.invoke(exception)
        cameraView.removeCameraListener(this)
        super.onCameraError(exception)
    }

    private fun analyzeFrame(frame: Frame) {
        if (analyzer == null) {
            return
        }
        with(frame) {
            val size = Size(size.width, size.height)

            if (dataClass == Image::class.java) {
                analyzer!!.analyze(
                    YUV420888MlKitImageBuilder(
                        getData() as Image,
                        size,
                        rotationToUser
                    )
                )
            } else {
                analyzer!!.analyze(
                    NV21MlKitImageBuilder(
                        getData() as ByteArray,
                        size,
                        rotationToUser
                    )
                )
            }
        }
    }

    override fun setZoom(value: Float) {
        // We ignore the check for zoom support when the camera is not initialized,
        // because at this moment there is no [CameraOptions] yet.
        if (cameraOptions?.isZoomSupported != false) {
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