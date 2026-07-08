package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import android.graphics.Point
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.components.ui.VisorView
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.FocusController
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.FocusView
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.platform.PlatformView

/** Android platform view that renders scanner preview and scanner overlays. */
class ScannerView(
    context: Context,
    private val scanner: Scanner,
    private val onDispose: (ScannerView) -> Unit,
) : FrameLayout(context), PlatformView, LifecycleOwner {
    private val focusView = FocusView(context)
    private val focusController = FocusController(this, focusView)
    private val lifecycleRegistry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.CREATED
    }

    private var visor: VisorView? = null
    private var isDisposed = false

    /** Lifecycle used by CameraX to bind camera resources to this platform view. */
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        layoutParams = matchParentLayoutParams()
        addView(scanner.previewView)
        addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) {
                updateScannerWidgetScale()
            }
        }
    }

    /** Starts scanner camera work and applies optional initial view settings. */
    fun startCamera(
        initialZoom: Float? = null,
        initialCropRect: RecognizeVisorCropRect? = null,
        onReady: OnInit,
        onError: OnError,
    ) {
        scanner.startCamera(
            lifecycleOwner = this,
            onInit = {
                bindFocus()
                try {
                    initialZoom?.let(::setZoom)
                    initialCropRect?.let(::setCropArea)
                } catch (e: Exception) {
                    onError.invoke(e)
                    return@startCamera
                }
                onReady.invoke()
            },
            onError = onError,
        )
        resumeCamera()
    }

    /** Moves the platform view lifecycle to the resumed state. */
    fun resumeCamera() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Moves the platform view lifecycle back to the created state. */
    fun pauseCamera() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    /** Returns true when the scanner camera is active. */
    fun isActive(): Boolean = scanner.isActive()

    /** Toggles the scanner camera torch. */
    fun toggleFlashLight() {
        scanner.toggleFlashLight()
    }

    /** Starts frame analysis and updates the visor state. */
    fun startScan(periodMs: Int) {
        scanner.startScan(periodMs)
        setVisorActive(scanner.isScanActive)
    }

    /** Pauses frame analysis and updates the visor state. */
    fun pauseScan() {
        scanner.pauseScan()
        setVisorActive(false)
    }

    /** Updates the delay between analysis attempts. */
    fun updateScanPeriod(periodMs: Int) {
        scanner.updateScanPeriod(periodMs)
    }

    /** Subscribes to decoded scanner results through the underlying scanner. */
    fun subscribeToScanResults(listener: (Barcode) -> Unit): () -> Unit = scanner.subscribeToScanResults(listener)

    /** Updates scanner crop settings and matching focus and visor UI. */
    fun setCropArea(cropRect: RecognizeVisorCropRect) {
        scanner.setCropArea(cropRect)
        updateScannerWidgetScale()
        focusController.updateCenter(cropRect.centerOffsetX.toFloat(), cropRect.centerOffsetY.toFloat())
        setVisorCropArea(cropRect, scanner.isScanActive)
    }

    /** Applies normalized zoom to the scanner camera. */
    fun setZoom(value: Float) {
        scanner.setZoom(value)
    }

    /** Connects focus UI callbacks to the active camera component. */
    private fun bindFocus() {
        addFocusView()
        focusController.bind(
            onAutoFocusRequest = { offsetX, offsetY ->
                scanner.focusOnCenter(AUTO_FOCUS_RESET_DELAY_MS, offsetX, offsetY)
            },
            onLockedFocusRequest = { offsetX, offsetY ->
                scanner.focusOnCenter(LOCKED_FOCUS_RESET_DELAY_MS, offsetX, offsetY)
            },
        )
    }

    /** Creates or updates the visor overlay for the requested crop area. */
    private fun setVisorCropArea(cropRect: RecognizeVisorCropRect, isScanActive: Boolean) {
        val activeVisor = visor ?: addVisor(cropRect)
        activeVisor.cropRect = cropRect
        activeVisor.isActive = isScanActive
    }

    /** Updates the visual active state of the current visor overlay. */
    private fun setVisorActive(isActive: Boolean) {
        visor?.isActive = isActive
    }

    /** Adds the focus overlay above scanner overlays. */
    private fun addFocusView() {
        if (focusView.parent === this) return
        addView(focusView)
    }

    /** Adds the visor below the focus overlay. */
    private fun addVisor(cropRect: RecognizeVisorCropRect): VisorView {
        return VisorView(cropRect, context).also { newVisor ->
            val focusIndex = indexOfChild(focusView)
            if (focusView.parent === this && focusIndex >= 0) {
                addView(newVisor, focusIndex)
            } else {
                addView(newVisor)
            }
            visor = newVisor
        }
    }

    /** Sends the current view-to-display scale to the scanner. */
    private fun updateScannerWidgetScale() {
        val (widthScale, heightScale) = calculateWidgetScale()
        scanner.setScale(widthScale, heightScale)
    }

    /** Calculates the ratio between this view size and the display size. */
    private fun calculateWidgetScale(): Pair<Double, Double> {
        val screenSize = getDisplaySize()
        if (screenSize.x == 0 || screenSize.y == 0) return Pair(1.0, 1.0)
        return Pair(
            measuredWidth.toDouble() / screenSize.x,
            measuredHeight.toDouble() / screenSize.y,
        )
    }

    /** Marks this platform view lifecycle as destroyed. */
    private fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /** Releases scanner resources, destroys the platform view lifecycle, and notifies the owner. */
    override fun dispose() {
        if (isDisposed) return

        isDisposed = true
        pauseCamera()
        scanner.dispose()
        destroy()
        onDispose.invoke(this)
    }

    /** Returns this native view to Flutter's platform view host. */
    override fun getView(): View = this

    /** Routes touch gestures to the focus overlay when it is attached. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (focusView.parent === this) {
            if (ev.action == MotionEvent.ACTION_UP) {
                performClick()
            }
            focusView.onTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Confirms accessibility click handling for focus touch events. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Reads the current display size used for scale calculations. */
    private fun getDisplaySize(): Point {
        val displayMetrics = resources.displayMetrics
        return Point(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    /** Creates layout parameters that fill the platform view container. */
    private fun matchParentLayoutParams(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
    )
    private companion object {
        /** No reset delay is used while the focus point is locked. */
        const val LOCKED_FOCUS_RESET_DELAY_MS = 0L

        /** Default reset delay after a regular autofocus tap. */
        const val AUTO_FOCUS_RESET_DELAY_MS = 3000L
    }
}
