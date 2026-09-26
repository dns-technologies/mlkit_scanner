package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.util.Rational
import android.view.Surface
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import io.flutter.view.TextureRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import java.util.concurrent.ExecutorService
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** Real CameraX use cases and LiveData; only platform boundaries are replaced. */
internal class XCameraFixture {
    val provider = mock(ProcessCameraProvider::class.java)
    val producer = mock(TextureRegistry.SurfaceProducer::class.java)
    lateinit var surfaceCallback: TextureRegistry.SurfaceProducer.Callback
    val nativeCamera = mock(Camera::class.java)
    val cameraInfo = mock(CameraInfo::class.java)
    val control = mock(CameraControl::class.java)
    val lifecycleOwner = mock(LifecycleOwner::class.java)
    val executor = mock(ExecutorService::class.java)
    val future = CameraTestFuture<ProcessCameraProvider>()
    val deviceState = RecordingLiveData(CameraState.create(CameraState.Type.OPEN))
    val zoomState = MutableLiveData<ZoomState>()
    val groups = mutableListOf<UseCaseGroup>()
    val errors = mutableListOf<Exception>()
    val availability = mutableListOf<CameraAvailability>()
    var initialized = 0
    var onInit: () -> Unit = {}
    var onAvailability: (CameraAvailability) -> Unit = {}
    var onFrame: OnCameraFrame = {}
    var bindAction: () -> Camera = { nativeCamera }
    val camera: XCamera

    init {
        doReturn(cameraInfo).`when`(nativeCamera).cameraInfo
        doReturn(control).`when`(nativeCamera).cameraControl
        doReturn(deviceState).`when`(cameraInfo).cameraState
        doReturn(zoomState).`when`(cameraInfo).zoomState
        doAnswer { it.getArgument<TextureRegistry.SurfaceProducer.Callback?>(0)?.let { callback -> surfaceCallback = callback }; null }.`when`(producer).setCallback(anyValue())
        doAnswer {
            groups += it.getArgument<UseCaseGroup>(2)
            bindAction()
        }.`when`(provider).bindToLifecycle(
            anyValue<LifecycleOwner>(), anyValue<CameraSelector>(), anyValue<UseCaseGroup>(),
        )
        camera = XCamera(RuntimeEnvironment.getApplication(), producer, {}) { future }
    }

    fun start(completeProvider: Boolean = true) {
        camera.bind(lifecycleOwner, executor, { onFrame(it) }, {
            availability += it
            onAvailability(it)
        }, {
            initialized++
            onInit()
        }, errors::add)
        if (completeProvider) future.complete(provider)
        ShadowLooper.idleMainLooper()
    }

    fun layout(width: Int = 100, height: Int = 100) { camera.updateGeometry(android.util.Size(width, height)) }

    fun analyzer(group: UseCaseGroup = groups.last()): ImageAnalysis.Analyzer {
        val analysis = group.useCases.filterIsInstance<ImageAnalysis>().single()
        // CameraX has no analyzer getter. Capture its installed callback to simulate work queued
        // before clearAnalyzer(), without copying the adapter's frame-handling logic into a fake.
        val field = ImageAnalysis::class.java.getDeclaredField("mSubscribedAnalyzer")
        field.isAccessible = true
        return field.get(analysis) as ImageAnalysis.Analyzer
    }

    class RecordingLiveData<T>(value: T) : MutableLiveData<T>(value) {
        val observers = mutableListOf<Observer<in T>>()

        override fun observeForever(observer: Observer<in T>) {
            observers += observer
            super.observeForever(observer)
        }
    }

    companion object {
        fun viewPort(rotation: Int = Surface.ROTATION_0, aspect: Rational = Rational(1, 1)) =
            ViewPort.Builder(aspect, rotation).build()
        fun <T> anyValue(): T = any<T>()
    }
}

internal fun withCameraFixture(block: (XCameraFixture) -> Unit) {
    val fixture = XCameraFixture()
    try {
        block(fixture)
    } finally {
        fixture.onAvailability = {}
        fixture.camera.dispose()
        ShadowLooper.idleMainLooper()
    }
}
