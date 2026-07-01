package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.CameraImageAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.components.camera.backend.CameraBackend
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.models.NV21AnalysingImage
import com.dns_technologies.mlkit_scanner.scanner.components.ui.CenterFocusView
import io.flutter.plugin.platform.PlatformView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receives normalized focus offsets and the delay after which focus should be reset.
 */
private typealias OnFocusCenterListener = (resetDelayMs: Long, offsetX: Float, offsetY: Float) -> Unit

/**
 * Scanner camera implementation that owns common plugin behavior.
 *
 * Camera-library-specific behavior is delegated to [CameraBackend]. This keeps analyzer lifecycle,
 * focus overlay, Flutter platform view plumbing, and resource cleanup independent from CameraX or
 * any future camera library.
 */
class ScannerCamera(
    context: Context,
    private val backend: CameraBackend,
    private val onDispose: OnDisposeCameraListener,
) : FrameLayout(context), PlatformView {
    /**
     * Non-null root Android view used by plugin code.
     */
    val cameraRootView: View
        get() = this

    /** Overlay responsible for drawing and handling the center-focus interaction. */
    private var centerFocusView: CenterFocusView? = null

    /** Callback invoked when the focus overlay asks the backend to focus at the center point. */
    private var onFocusCenter: OnFocusCenterListener? = null

    /** Executor used by image analysis. It is recreated after every camera release. */
    private var analysisExecutor: ExecutorService? = null

    /** Active ML Kit analyzer receiving preview frames. */
    private var analyzer: CameraImageAnalyzer? = null

    /** Last requested focus offset, restored when the backend is rebound. */
    private var focusCenter: Pair<Float, Float> = INITIAL_FOCUS_CENTER

    init {
        layoutParams = matchParentLayoutParams()
        addView(backend.previewView)
    }

    /**
     * Starts the delegated camera backend and wires it to common frame handling.
     */
    fun startCamera(lifecycleOwner: LifecycleOwner, onInit: OnInit, onError: OnError) {
        backend.start(
            lifecycleOwner = lifecycleOwner,
            analysisExecutor = ensureAnalysisExecutor(),
            onFrame = this::analyzeFrame,
            onInit = {
                useCenterFocus(focusCenter.first, focusCenter.second, backend::focusOnCenter)
                onInit.invoke()
            },
            onError = onError,
        )
    }

    /**
     * Returns true when the delegated backend has an active camera binding.
     */
    fun isActive(): Boolean = backend.isActive()

    /**
     * Toggles the active backend torch.
     */
    fun toggleFlashLight() {
        backend.toggleFlashLight()
    }

    /**
     * Stores and applies the focus point offset used by the center-focus overlay.
     */
    fun changeFocusCenter(widthOffset: Float, heightOffset: Float) {
        focusCenter = Pair(widthOffset, heightOffset)
        applyFocusCenterToOverlay(widthOffset, heightOffset)
    }

    /**
     * Attaches an analyzer that receives backend frames.
     */
    fun attachAnalyser(analyzer: CameraImageAnalyzer) {
        this.analyzer = analyzer
    }

    /**
     * Stops sending backend frames to an analyzer.
     */
    fun clearAnalyzer() {
        analyzer = null
    }

    /**
     * Sets linear zoom in the 0..1 range through the delegated backend.
     */
    fun setZoom(value: Float) {
        backend.setZoom(value)
    }

    /**
     * Adds scanner UI over the preview while keeping the focus overlay above it.
     */
    fun addOverlay(view: View) {
        val focusView = centerFocusView
        if (focusView != null) {
            addView(view, indexOfChild(focusView))
        } else {
            addView(view)
        }
    }

    /**
     * Releases common scanner resources and the delegated backend.
     */
    fun releaseCamera() {
        clearAnalyzer()
        backend.release()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
    }

    /**
     * Releases the platform view and notifies the plugin that this camera is gone.
     */
    override fun dispose() {
        releaseCamera()
        onDispose.invoke(this)
    }

    /**
     * Returns the Android view rendered by Flutter.
     */
    override fun getView(): View = this

    /**
     * Routes touch events to the focus overlay when it is enabled.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val focusView = centerFocusView ?: return super.dispatchTouchEvent(ev)
        handleFocusTouch(ev, focusView)
        return true
    }

    /**
     * Keeps accessibility click handling valid for touch events intercepted by this view.
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Recreates the analysis executor after a camera release. */
    private fun ensureAnalysisExecutor(): ExecutorService {
        val activeExecutor = analysisExecutor
        if (activeExecutor != null && !activeExecutor.isShutdown) return activeExecutor

        return Executors.newSingleThreadExecutor().also {
            analysisExecutor = it
        }
    }

    /** Sends a backend frame to the active ML Kit analyzer. */
    private fun analyzeFrame(image: NV21AnalysingImage) {
        val activeAnalyzer = analyzer
        if (activeAnalyzer != null) {
            activeAnalyzer.analyze(image)
        }
    }

    /** Enables the center-focus overlay and applies the initial adaptive focus offset. */
    private fun useCenterFocus(
        widthOffset: Float,
        heightOffset: Float,
        onFocusCenter: OnFocusCenterListener,
    ) {
        this.onFocusCenter = onFocusCenter
        ensureCenterFocusView()
        applyFocusCenterToOverlay(widthOffset, heightOffset)
    }

    /** Recalculates focus coordinates for the current view size and screen orientation. */
    private fun applyFocusCenterToOverlay(widthOffset: Float, heightOffset: Float) {
        if (width == 0 || height == 0) {
            scheduleFocusCenterChange(widthOffset, heightOffset)
            return
        }

        centerFocusView?.applyFocusCenter(widthOffset, heightOffset)
    }

    /** Creates the focus overlay once and keeps it above the preview. */
    private fun ensureCenterFocusView() {
        if (centerFocusView != null) return
        centerFocusView = CenterFocusView(context, INITIAL_FOCUS_CENTER).also(::addView)
    }

    /** Posts focus recalculation until the Android view has a measured size. */
    private fun scheduleFocusCenterChange(widthOffset: Float, heightOffset: Float) {
        post { applyFocusCenterToOverlay(widthOffset, heightOffset) }
    }

    /** Applies adaptive focus coordinates and wires the overlay callback to backend focus. */
    private fun CenterFocusView.applyFocusCenter(widthOffset: Float, heightOffset: Float) {
        val (horizontalOffset, verticalOffset) = calcAdaptiveOffsets(
            resources.configuration.orientation,
            width,
            widthOffset,
            height,
            heightOffset,
        )
        setAutoFocusSetListener { needLock ->
            onFocusCenter?.invoke(
                if (needLock) LOCKED_FOCUS_RESET_DELAY_MS else AUTO_FOCUS_RESET_DELAY_MS,
                horizontalOffset,
                verticalOffset,
            )
        }
        setFocusCenter(horizontalOffset, verticalOffset)
    }

    /** Forwards the touch event to the focus overlay and reports click completion. */
    private fun handleFocusTouch(event: MotionEvent, focusView: CenterFocusView) {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        focusView.onTouchEvent(event)
    }

    /** Converts scanner offsets into preview coordinates for portrait and landscape layouts. */
    private fun calcAdaptiveOffsets(
        orientation: Int,
        width: Int,
        offsetWidth: Float,
        height: Int,
        offsetHeight: Float,
    ): Pair<Float, Float> = when (orientation) {
        Configuration.ORIENTATION_PORTRAIT -> Pair(width / 2F * offsetWidth, height / 2F * offsetHeight)
        else -> Pair(width / 2F * -offsetHeight, height / 2F * -offsetWidth)
    }

    /** Creates full-size layout params used by the root view. */
    private fun matchParentLayoutParams(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private companion object {
        /** Initial focus overlay offset before scanner-specific offsets are applied. */
        val INITIAL_FOCUS_CENTER = Pair(0.0F, 0.0F)

        /** No reset delay is used while the focus point is locked. */
        const val LOCKED_FOCUS_RESET_DELAY_MS = 0L

        /** Default reset delay after a regular autofocus tap. */
        const val AUTO_FOCUS_RESET_DELAY_MS = 3000L
    }
}
