package com.dns_technologies.mlkit_scanner.session

import android.os.Handler
import androidx.camera.core.CameraControl
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

internal class ScannerSessionImplTest {
    @Test
    fun `registering views does not choose a preview host`() {
        val fixture = Fixture()
        val first = fixture.view(FIRST_VIEW_ID)
        val second = fixture.attach(SECOND_VIEW_ID)

        verify(first, never()).attachPreview(anyValue())
        verify(second, never()).attachPreview(anyValue())
        assertFalse(fixture.hasPreview(FIRST_VIEW_ID))
        assertFalse(fixture.hasPreview(SECOND_VIEW_ID))
        verify(first, never()).disposeFromSession()
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `initial crop is retained by view immediately during registration`() {
        val fixture = Fixture()
        val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)

        val view = fixture.attach(SECOND_VIEW_ID, initialCropRect = cropRect)

        verify(view).setCropArea(cropRect)
        verify(fixture.scanner, never()).setCropArea(cropRect)
        verify(view, never()).attachPreview(anyValue())
    }

    @Test
    fun `removing view state without preview does not disturb preview host`() {
        val fixture = Fixture()
        val first = fixture.view(FIRST_VIEW_ID)
        val second = fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(FIRST_VIEW_ID)
        clearInvocations(first)

        fixture.session.disposeView(SECOND_VIEW_ID)

        verify(second, never()).disposeFromSession()
        verify(first, never()).detachPreview()
        verify(first, never()).attachPreview(anyValue())
        assertTrue(fixture.hasPreview(FIRST_VIEW_ID))
        assertTrue(fixture.delayedCallbacks.isEmpty())
    }

    @Test
    fun `disposing A cancels its pending startup and lets B capture without an error`() =
        runSessionTest {
            val fixture = Fixture()
            fixture.attach(SECOND_VIEW_ID)
            val firstZoom = CompletableDeferred<Unit>()
            fixture.enqueueZoomResult(firstZoom)
            val firstCapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()

            assertFalse(firstCapture.isCompleted)
            fixture.session.disposeView(FIRST_VIEW_ID)
            val secondCapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(SECOND_VIEW_ID, null, null)
            }

            withTimeout(TEST_TIMEOUT_MS) { awaitAll(firstCapture, secondCapture) }

            assertTrue(firstZoom.isCancelled)
            assertTrue(fixture.hasPreview(SECOND_VIEW_ID))
            verify(fixture.scanner).showPreview()
        }

    @Test
    fun `late disposal from an old platform view cannot remove its replacement`() =
        runSessionTest {
            val fixture = Fixture()
            val oldView = fixture.view(FIRST_VIEW_ID)
            fixture.session.disposeView(FIRST_VIEW_ID)
            val replacement = fixture.attach(FIRST_VIEW_ID)

            fixture.session.disposeView(FIRST_VIEW_ID, oldView)
            val capture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { capture.await() }

            verify(replacement).attachPreview(anyValue())
            assertTrue(fixture.hasPreview(FIRST_VIEW_ID))
        }

    @Test
    fun `removing preview host does not infer a replacement`() {
        val fixture = Fixture()
        val first = fixture.view(FIRST_VIEW_ID)
        val second = fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        clearInvocations(first)

        fixture.session.disposeView(SECOND_VIEW_ID)

        verify(second, never()).disposeFromSession()
        verify(first, never()).attachPreview(anyValue())
        assertFalse(fixture.hasPreview(FIRST_VIEW_ID))
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `capture selects exact view after arbitrary removals`() {
        val fixture = Fixture()
        val first = fixture.view(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        fixture.attach(THIRD_VIEW_ID)
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.activateCamera(THIRD_VIEW_ID)
        clearInvocations(first)

        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.session.disposeView(THIRD_VIEW_ID)

        verify(first, never()).attachPreview(anyValue())
        assertFalse(fixture.hasPreview(FIRST_VIEW_ID))

        runBlocking { fixture.captureCamera(FIRST_VIEW_ID, null, null) }

        verify(first).attachPreview(anyValue())
        assertTrue(fixture.hasPreview(FIRST_VIEW_ID))
    }

    @Test
    fun `last view schedules release and a new view cancels it`() {
        val fixture = Fixture()

        fixture.session.disposeView(FIRST_VIEW_ID)
        val releaseTask = fixture.delayedCallbacks.single()
        fixture.attach(SECOND_VIEW_ID)
        releaseTask.run()

        verify(fixture.mainHandler).removeCallbacks(releaseTask)
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `session releases shared pipeline after registry remains empty`() {
        val fixture = Fixture()

        fixture.session.disposeView(FIRST_VIEW_ID)
        fixture.delayedCallbacks.single().run()

        verify(fixture.scanner).dispose()
        assertEquals(1, fixture.subscriptionCancelCalls)
        assertEquals(1, fixture.releaseCalls)
    }

    @Test
    fun `camera release system pauses scan but keeps registered session warm`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.releaseCamera(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, atLeastOnce()).pauseScan()
        verify(fixture.scanner).hidePreview()
        verify(fixture.scanner, never()).dispose()
        assertEquals(
            listOf(ScannerSessionImpl.CAMERA_HANDOFF_GRACE_PERIOD_MS),
            fixture.scheduledDelays,
        )
        val handoffStop = fixture.delayedCallbacks.single()

        clearInvocations(fixture.scanner)
        fixture.captureCamera(FIRST_VIEW_ID, null, null)
        handoffStop.run()

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.mainHandler).removeCallbacks(handoffStop)
        verify(fixture.scanner).setZoomRatio(1.0F)
        verify(fixture.scanner).setTorch(false)
        verify(fixture.scanner).showPreview()
        verify(fixture.scanner, atLeastOnce()).resumeScan()
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `handoff keeps lifecycle resumed while the new owner requests permission`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        fixture.session.releaseCamera(FIRST_VIEW_ID)
        val handoffStop = fixture.delayedCallbacks.single()
        val permission = CompletableDeferred<Boolean>()

        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.captureCamera(SECOND_VIEW_ID) { permission.await() }
        }

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.mainHandler).removeCallbacks(handoffStop)
        handoffStop.run()
        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)

        permission.complete(true)
        withTimeout(TEST_TIMEOUT_MS) { capture.await() }
        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
    }

    @Test
    fun `permission denial reports error without starting camera`() = runSessionTest {
        val fixture = Fixture()

        val error = runCatching {
            fixture.session.captureCamera(FIRST_VIEW_ID) { false }
        }.exceptionOrNull()

        assertSame(PluginError.AuthorizationCameraError, error)
        assertTrue(fixture.hasPreview(FIRST_VIEW_ID))
        assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
        assertEquals(0, fixture.startCalls)
    }

    @Test
    fun `release lets pending view initialization finish without applying it`() = runSessionTest {
        val fixture = Fixture()
        val permission = CompletableDeferred<Boolean>()
        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.captureCamera(FIRST_VIEW_ID) { permission.await() }
        }

        fixture.session.releaseCamera(FIRST_VIEW_ID)
        permission.complete(true)
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { capture.await() }

        assertFalse(fixture.hasPreview(FIRST_VIEW_ID))
        assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
        assertEquals(1, fixture.startCalls)
        verify(fixture.scanner, never()).showPreview()
    }

    @Test
    fun `A B A waits for first A initialization before applying retained state`() =
        runSessionTest {
            val fixture = Fixture()
            fixture.attach(SECOND_VIEW_ID)
            val firstPermission = CompletableDeferred<Boolean>()
            var firstViewPermissionRequests = 0
            val firstCapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.captureCamera(FIRST_VIEW_ID) {
                    firstViewPermissionRequests += 1
                    firstPermission.await()
                }
            }

            val secondCapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(SECOND_VIEW_ID, null, null)
            }
            val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)
            clearInvocations(fixture.scanner)

            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
            fixture.session.setCropArea(FIRST_VIEW_ID, cropRect)
            fixture.session.startScan(FIRST_VIEW_ID, 250)
            val returnedCapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.captureCamera(FIRST_VIEW_ID) {
                    error("The returning view must await its first initialization")
                }
            }

            verify(fixture.scanner, never()).setZoomRatio(0.75F)
            verify(fixture.scanner, never()).setTorch(true)
            verify(fixture.scanner, never()).setCropArea(cropRect)
            verify(fixture.scanner, never()).updateScanPeriod(250)
            assertFalse(firstCapture.isCompleted)
            assertFalse(returnedCapture.isCompleted)

            firstPermission.complete(true)
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) {
                awaitAll(firstCapture, secondCapture, returnedCapture)
            }

            assertEquals(1, firstViewPermissionRequests)
            assertTrue(fixture.hasPreview(FIRST_VIEW_ID))
            verify(fixture.scanner).setZoomRatio(0.75F)
            verify(fixture.scanner).setTorch(true)
            verify(fixture.scanner).setCropArea(cropRect)
            verify(fixture.scanner).updateScanPeriod(250)
            verify(fixture.scanner, atLeastOnce()).resumeScan()
        }

    @Test
    fun `fast A B completes A initialization without applying A state to B`() =
        runSessionTest {
            val fixture = Fixture()
            fixture.attach(SECOND_VIEW_ID)
            val firstPermission = CompletableDeferred<Boolean>()
            val firstCapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.captureCamera(FIRST_VIEW_ID) { firstPermission.await() }
            }
            val secondCapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(SECOND_VIEW_ID, null, null)
            }
            val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)
            clearInvocations(fixture.scanner)

            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
            fixture.session.setCropArea(FIRST_VIEW_ID, cropRect)
            fixture.session.startScan(FIRST_VIEW_ID, 250)
            firstPermission.complete(true)
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { awaitAll(firstCapture, secondCapture) }

            assertTrue(fixture.hasPreview(SECOND_VIEW_ID))
            verify(fixture.scanner, never()).setZoomRatio(0.75F)
            verify(fixture.scanner, never()).setTorch(true)
            verify(fixture.scanner, never()).setCropArea(cropRect)
            verify(fixture.scanner, never()).updateScanPeriod(250)
        }

    @Test
    fun `resume without capture retains intent until that view captures camera`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.pauseCamera(FIRST_VIEW_ID)
        fixture.session.releaseCamera(FIRST_VIEW_ID)
        clearInvocations(fixture.scanner)
        clearInvocations(fixture.view(FIRST_VIEW_ID))

        fixture.session.resumeCamera(FIRST_VIEW_ID)

        verify(fixture.view(FIRST_VIEW_ID), never()).attachPreview(anyValue())
        verify(fixture.scanner, never()).showPreview()
        assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)

        fixture.captureCamera(FIRST_VIEW_ID, null, null)

        verify(fixture.view(FIRST_VIEW_ID)).attachPreview(anyValue())
        verify(fixture.scanner).showPreview()
        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
    }

    @Test
    fun `navigation gap pauses camera and scan while retaining shared resources`() =
        runSessionTest {
            val fixture = Fixture()
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            fixture.session.startScan(FIRST_VIEW_ID, 100)
            clearInvocations(fixture.scanner)

            fixture.session.disposeView(FIRST_VIEW_ID)

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            assertEquals(
                listOf(
                    ScannerSessionImpl.CAMERA_HANDOFF_GRACE_PERIOD_MS,
                    ScannerSessionImpl.NAVIGATION_GRACE_PERIOD_MS,
                ),
                fixture.scheduledDelays,
            )
            fixture.delayedCallbacks.first().run()
            assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner, atLeastOnce()).pauseScan()
            verify(fixture.scanner, never()).dispose()
        }

    @Test
    fun `new view during grace reuses camera and waits for preview before starting its scan`() =
        runSessionTest {
            val fixture = Fixture()
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            fixture.session.startScan(FIRST_VIEW_ID, 100)
            fixture.session.disposeView(FIRST_VIEW_ID)
            val releaseTask = fixture.delayedCallbacks.last()
            clearInvocations(fixture.scanner)

            fixture.attach(SECOND_VIEW_ID, previewReady = false)

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner, never()).resumeScan()
            verify(fixture.mainHandler).removeCallbacks(releaseTask)

            fixture.captureCamera(SECOND_VIEW_ID, 0.75, null)
            fixture.session.startScan(SECOND_VIEW_ID, 100)

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner, never()).resumeScan()

            fixture.markPreviewReady(SECOND_VIEW_ID)

            verify(fixture.scanner).resumeScan()
            releaseTask.run()

            assertEquals(1, fixture.startCalls)
            verify(fixture.scanner, never()).dispose()
        }

    @Test
    fun `new preview host waits for open camera before applying controls`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        val cameraOpen = CompletableDeferred<Unit>()
        fixture.enqueueOpenResult(cameraOpen)
        clearInvocations(fixture.scanner)

        val activation = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(
                SECOND_VIEW_ID,
                initialZoomRatio = 0.75,
                initialCropRect = null,
                initialFlashEnabled = true,
            )
        }

        verify(fixture.scanner, never()).setZoomRatio(0.75F)
        verify(fixture.scanner, never()).setTorch(true)
        verify(fixture.scanner, never()).showPreview()
        assertFalse(activation.isCompleted)

        cameraOpen.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { activation.await() }

        verify(fixture.scanner).setZoomRatio(0.75F)
        verify(fixture.scanner).setTorch(true)
        verify(fixture.scanner).showPreview()
    }

    @Test
    fun `open completion ignores controls of preview host replaced while waiting`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        fixture.attach(THIRD_VIEW_ID)
        val cameraOpen = CompletableDeferred<Unit>()
        fixture.enqueueOpenResult(cameraOpen)
        clearInvocations(fixture.scanner)

        val secondActivation = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(SECOND_VIEW_ID, 0.25, null)
        }
        val thirdActivation = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(THIRD_VIEW_ID, 0.75, null)
        }

        cameraOpen.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(secondActivation, thirdActivation) }

        verify(fixture.scanner, never()).setZoomRatio(0.25F)
        verify(fixture.scanner).setZoomRatio(0.75F)
        verify(fixture.scanner, times(1)).showPreview()
    }

    @Test
    fun `obsolete control failure is ignored after new owner preempts it`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        val obsoleteTorch = CompletableDeferred<Unit>()
        fixture.enqueueTorchResult(obsoleteTorch)
        clearInvocations(fixture.scanner)

        val firstCaptureError = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }.exceptionOrNull()
        }
        verify(fixture.scanner).setTorch(false)
        assertFalse(firstCaptureError.isCompleted)

        val secondCapture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(SECOND_VIEW_ID, null, null)
        }
        val cause = IllegalStateException("Camera is not active")
        obsoleteTorch.completeExceptionally(
            PluginError.CameraControlError(
                CameraControlOperation.TORCH,
                cause = cause,
            ),
        )

        withTimeout(TEST_TIMEOUT_MS) { secondCapture.await() }
        val error = withTimeout(TEST_TIMEOUT_MS) { firstCaptureError.await() }

        assertEquals(null, error)
        assertTrue(fixture.hasPreview(SECOND_VIEW_ID))
        verify(fixture.scanner, times(1)).showPreview()
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `late camera pause from covered view does not pause current view`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        fixture.attach(SECOND_VIEW_ID)
        fixture.captureCamera(SECOND_VIEW_ID, null, null)
        fixture.session.startScan(SECOND_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.pauseCamera(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, never()).pauseScan()

        fixture.session.disposeView(SECOND_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, atLeastOnce()).pauseScan()
        clearInvocations(fixture.scanner)

        fixture.session.resumeCamera(FIRST_VIEW_ID)
        fixture.captureCamera(FIRST_VIEW_ID, null, null)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `release from previous camera owner does not release current view`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val secondView = fixture.attach(SECOND_VIEW_ID)
        fixture.captureCamera(SECOND_VIEW_ID, null, null)
        clearInvocations(fixture.scanner)
        clearInvocations(secondView)

        fixture.session.releaseCamera(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        assertTrue(fixture.hasPreview(SECOND_VIEW_ID))
        verify(fixture.scanner, never()).hidePreview()
        verify(fixture.scanner, never()).pauseScan()
        verify(secondView, never()).detachPreview()
    }

    @Test
    fun `scan pause belongs to its view and is restored when that view returns`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        fixture.attach(SECOND_VIEW_ID)
        fixture.captureCamera(SECOND_VIEW_ID, null, null)
        fixture.session.startScan(SECOND_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.pauseScan(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, never()).pauseScan()

        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.session.resumeCamera(FIRST_VIEW_ID)
        fixture.captureCamera(FIRST_VIEW_ID, null, null)

        verify(fixture.scanner, atLeastOnce()).pauseScan()
        clearInvocations(fixture.scanner)

        fixture.session.startScan(FIRST_VIEW_ID, 100)

        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `active view configuration commands update shared pipeline`() = runSessionTest {
        val fixture = Fixture()
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)
        clearInvocations(fixture.scanner)

        fixture.session.setZoomRatio(SECOND_VIEW_ID, 0.75F)
        fixture.session.updateScanPeriod(SECOND_VIEW_ID, 250)
        fixture.session.setCropArea(SECOND_VIEW_ID, cropRect)
        fixture.session.startScan(SECOND_VIEW_ID, 400)
        fixture.session.toggleFlashLight(SECOND_VIEW_ID)

        verify(fixture.scanner).setZoomRatio(0.75F)
        verify(fixture.scanner, atLeastOnce()).updateScanPeriod(250)
        verify(fixture.scanner).updateScanPeriod(400)
        verify(fixture.view(SECOND_VIEW_ID)).setCropArea(cropRect)
        verify(fixture.scanner, atLeastOnce()).resumeScan()
        verify(fixture.scanner).setTorch(true)
    }

    @Test
    fun `runtime crop updates only crop`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.updateScanPeriod(FIRST_VIEW_ID, 250)
        clearInvocations(fixture.scanner)
        val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)

        fixture.session.setCropArea(FIRST_VIEW_ID, cropRect)

        verify(fixture.scanner).setCropArea(cropRect)
        verify(fixture.scanner, never()).updateScanPeriod(anyInt())
    }

    @Test
    fun `runtime delay updates only delay`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.setCropArea(
            FIRST_VIEW_ID,
            RecognizeVisorCropRect(scaleWidth = 0.5),
        )
        clearInvocations(fixture.scanner)

        fixture.session.updateScanPeriod(FIRST_VIEW_ID, 250)

        verify(fixture.scanner).updateScanPeriod(250)
        verify(fixture.scanner, never()).setCropArea(anyValue())
    }

    @Test
    fun `pending runtime zoomRatio keeps scan and visor active`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        val completion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(completion)
        clearInvocations(fixture.scanner, fixture.view(FIRST_VIEW_ID))

        val zoomRatio = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.5F)
        }

        verify(fixture.scanner).setZoomRatio(0.5F)
        verify(fixture.scanner, never()).setTorch(anyBoolean())
        verify(fixture.scanner, never()).resetFocus()
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner, never()).pauseScan()
        verify(fixture.view(FIRST_VIEW_ID), never()).setScanActive(false)

        completion.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { zoomRatio.await() }

        verify(fixture.scanner, never()).pauseScan()
        verify(fixture.view(FIRST_VIEW_ID), never()).setScanActive(false)
    }

    @Test
    fun `pending runtime torch keeps scan and visor active`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        val completion = CompletableDeferred<Unit>()
        fixture.enqueueTorchResult(completion)
        clearInvocations(fixture.scanner, fixture.view(FIRST_VIEW_ID))

        val torch = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        }

        verify(fixture.scanner).setTorch(true)
        verify(fixture.scanner, never()).setZoomRatio(anyFloat())
        verify(fixture.scanner, never()).resetFocus()
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner, never()).pauseScan()
        verify(fixture.view(FIRST_VIEW_ID), never()).setScanActive(false)

        completion.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { torch.await() }

        verify(fixture.scanner, never()).pauseScan()
        verify(fixture.view(FIRST_VIEW_ID), never()).setScanActive(false)
    }

    @Test
    fun `pending runtime focus keeps scan and visor active`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        val completion = CompletableDeferred<Unit>()
        fixture.enqueueFocusResult(completion)
        clearInvocations(fixture.scanner, fixture.view(FIRST_VIEW_ID))

        fixture.session.requestFocus(
            viewId = FIRST_VIEW_ID,
            resetDelayMs = 500L,
            offsetX = 10F,
            offsetY = 20F,
        )

        verify(fixture.scanner).focusOnCenter(500L, 10F, 20F)
        verify(fixture.scanner, never()).setZoomRatio(anyFloat())
        verify(fixture.scanner, never()).setTorch(anyBoolean())
        verify(fixture.scanner, never()).resetFocus()
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner, never()).pauseScan()
        verify(fixture.view(FIRST_VIEW_ID), never()).setScanActive(false)

        completion.complete(Unit)
        yield()

        verify(fixture.scanner, never()).pauseScan()
        verify(fixture.view(FIRST_VIEW_ID), never()).setScanActive(false)
    }

    @Test
    fun `runtime focus is not retained for the next camera open`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.requestFocus(
            viewId = FIRST_VIEW_ID,
            resetDelayMs = 500L,
            offsetX = 10F,
            offsetY = 20F,
        )
        yield()
        clearInvocations(fixture.scanner)

        fixture.emitCameraClosed()
        fixture.emitCameraOpen()

        verify(fixture.scanner).resetFocus()
        verify(fixture.scanner, never()).focusOnCenter(anyLong(), anyFloat(), anyFloat())
    }

    @Test
    fun `configuration is retained before first camera capture`() = runSessionTest {
        val fixture = Fixture()
        val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)
        clearInvocations(fixture.scanner)

        fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.5F)
        fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        fixture.session.setCropArea(FIRST_VIEW_ID, cropRect)
        fixture.session.startScan(FIRST_VIEW_ID, 250)

        verify(fixture.scanner, never()).setZoomRatio(0.5F)
        verify(fixture.scanner, never()).setTorch(true)
        verify(fixture.scanner, never()).setCropArea(cropRect)
        verify(fixture.scanner, never()).updateScanPeriod(250)

        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { capture.await() }

        verify(fixture.scanner).setZoomRatio(0.5F)
        verify(fixture.scanner).setTorch(true)
        verify(fixture.scanner).setCropArea(cropRect)
        verify(fixture.scanner).updateScanPeriod(250)
        verify(fixture.scanner, atLeastOnce()).resumeScan()
    }

    @Test
    fun `runtime zoomRatio does not report success after session release`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val completion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(completion)
        val zoomRatio = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.5F)
        }

        fixture.session.release()
        completion.complete(Unit)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { zoomRatio.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
    }

    @Test
    fun `new desired torch state cancels the pending camera command`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val firstCompletion = CompletableDeferred<Unit>()
        fixture.enqueueTorchResult(firstCompletion)
        clearInvocations(fixture.scanner)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        }

        verify(fixture.scanner).setTorch(true)
        verify(fixture.scanner).setTorch(false)
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        assertTrue(firstCompletion.isCancelled)

        firstCompletion.complete(Unit)
        verify(fixture.scanner, times(1)).setTorch(false)
    }

    @Test
    fun `failed torch update keeps requested state for the next change`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val completion = CompletableDeferred<Unit>()
        fixture.enqueueTorchResult(completion)
        clearInvocations(fixture.scanner)
        val toggle = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        }

        completion.completeExceptionally(PluginError.DeviceHasNotFlash)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { toggle.await() }
        }.exceptionOrNull()
        assertSame(PluginError.DeviceHasNotFlash, error)
        clearInvocations(fixture.scanner)

        fixture.session.toggleFlashLight(FIRST_VIEW_ID)

        verify(fixture.scanner).setTorch(false)
        verify(fixture.scanner, never()).setTorch(true)
    }

    @Test
    fun `inactive torch is retained without touching active camera hardware`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        fixture.captureCamera(SECOND_VIEW_ID, null, null)
        clearInvocations(fixture.scanner)

        fixture.session.toggleFlashLight(FIRST_VIEW_ID)

        verify(fixture.scanner, never()).setTorch(true)
        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.captureCamera(FIRST_VIEW_ID, null, null)
        verify(fixture.scanner).setTorch(true)
    }

    @Test
    fun `new desired zoomRatio cancels the pending camera command`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val firstCompletion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(firstCompletion)
        clearInvocations(fixture.scanner)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.25F)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
        }

        verify(fixture.scanner, times(1)).setZoomRatio(0.25F)
        verify(fixture.scanner).setZoomRatio(0.75F)
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        assertTrue(firstCompletion.isCancelled)

        firstCompletion.completeExceptionally(
            PluginError.CameraControlError(
                operation = CameraControlOperation.ZOOM,
                cause = CameraControl.OperationCanceledException("new desired value won"),
            ),
        )
        verify(fixture.scanner, times(1)).setZoomRatio(0.75F)
    }

    @Test
    fun `recapturing the same view cancels its old command before restoring it`() =
        runSessionTest {
            val fixture = Fixture()
            fixture.activateCamera(FIRST_VIEW_ID)
            val oldZoom = CompletableDeferred<Unit>()
            fixture.enqueueZoomResult(oldZoom)
            clearInvocations(fixture.scanner)

            val zoom = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
            }
            val recapture = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }

            withTimeout(TEST_TIMEOUT_MS) { awaitAll(zoom, recapture) }

            assertTrue(oldZoom.isCancelled)
            verify(fixture.scanner, times(2)).setZoomRatio(0.75F)
            verify(fixture.scanner).showPreview()
        }

    @Test
    fun `current canceled operation retries only after the next stable open`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val canceledZoom = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(canceledZoom)
        clearInvocations(fixture.scanner)

        val zoomRatio = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
        }
        canceledZoom.completeExceptionally(
            PluginError.CameraControlError(
                operation = CameraControlOperation.ZOOM,
                cause = CameraControl.OperationCanceledException("camera was displaced"),
            ),
        )
        yield()

        assertFalse(zoomRatio.isCompleted)
        verify(fixture.scanner, times(1)).setZoomRatio(0.75F)

        fixture.emitCameraClosed()
        verify(fixture.scanner, times(1)).setZoomRatio(0.75F)

        fixture.emitCameraOpen()
        withTimeout(TEST_TIMEOUT_MS) { zoomRatio.await() }

        verify(fixture.scanner, times(2)).setZoomRatio(0.75F)
    }

    @Test
    fun `open applies one current owner snapshot in deterministic order`() = runSessionTest {
        val fixture = Fixture()
        val crop = RecognizeVisorCropRect(scaleWidth = 0.5)
        fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
        fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        fixture.session.setCropArea(FIRST_VIEW_ID, crop)
        fixture.session.startScan(FIRST_VIEW_ID, 250)
        clearInvocations(fixture.scanner)

        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { capture.await() }

        inOrder(fixture.scanner).apply {
            verify(fixture.scanner).resetFocus()
            verify(fixture.scanner).setCropArea(crop)
            verify(fixture.scanner).updateScanPeriod(250)
            verify(fixture.scanner).setZoomRatio(0.75F)
            verify(fixture.scanner).setTorch(true)
            verify(fixture.scanner).showPreview()
        }
    }

    @Test
    fun `new owner preempts pending A zoomRatio without waiting for its completion`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        val firstZoomCompletion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(firstZoomCompletion)
        clearInvocations(fixture.scanner)

        val firstZoomError = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
            }.exceptionOrNull()
        }
        val secondCapture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(SECOND_VIEW_ID, null, null)
        }

        verify(fixture.scanner).setZoomRatio(0.75F)
        withTimeout(TEST_TIMEOUT_MS) { secondCapture.await() }
        val error = withTimeout(TEST_TIMEOUT_MS) { firstZoomError.await() }

        assertEquals(null, error)
        assertTrue(firstZoomCompletion.isCancelled)
        verify(fixture.scanner).setZoomRatio(1.0F)
        assertTrue(fixture.hasPreview(SECOND_VIEW_ID))

        firstZoomCompletion.completeExceptionally(
            PluginError.CameraControlError(
                CameraControlOperation.ZOOM,
                cause = IllegalStateException("Camera is not active"),
            ),
        )
        clearInvocations(fixture.scanner)
        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.captureCamera(FIRST_VIEW_ID, null, null)
        verify(fixture.scanner).setZoomRatio(0.75F)
    }

    @Test
    fun `old A controls during B startup only update A retained state`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        val secondFocus = CompletableDeferred<Unit>()
        fixture.enqueueFocusReset(secondFocus)
        clearInvocations(fixture.scanner)

        val secondCapture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(SECOND_VIEW_ID, null, null)
        }
        verify(fixture.scanner).resetFocus()
        assertFalse(secondCapture.isCompleted)

        fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
        fixture.session.toggleFlashLight(FIRST_VIEW_ID)

        verify(fixture.scanner, never()).setZoomRatio(0.75F)
        verify(fixture.scanner, never()).setTorch(true)

        secondFocus.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { secondCapture.await() }
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.captureCamera(FIRST_VIEW_ID, null, null)

        verify(fixture.scanner).setZoomRatio(0.75F)
        verify(fixture.scanner).setTorch(true)
    }

    @Test
    fun `latest A capture preempts both pending A and B executions`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        val oldZoom = CompletableDeferred<Unit>()
        val secondFocus = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(oldZoom)
        fixture.enqueueFocusReset(secondFocus)
        clearInvocations(fixture.scanner)

        val oldA = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
        }
        val captureB = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(SECOND_VIEW_ID, null, null)
        }
        val latestA = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }

        withTimeout(TEST_TIMEOUT_MS) { awaitAll(oldA, captureB, latestA) }

        assertTrue(oldZoom.isCancelled)
        assertTrue(secondFocus.isCancelled)
        assertTrue(fixture.hasPreview(FIRST_VIEW_ID))
        assertFalse(fixture.hasPreview(SECOND_VIEW_ID))
        verify(fixture.scanner, atLeastOnce()).setZoomRatio(0.75F)

        oldZoom.complete(Unit)
        secondFocus.complete(Unit)
    }

    @Test
    fun `new owner waits for previous focus reset before revealing preview`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        val focusReset = CompletableDeferred<Unit>()
        fixture.enqueueFocusReset(focusReset)
        clearInvocations(fixture.scanner)

        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(SECOND_VIEW_ID, null, null)
        }

        verify(fixture.scanner).resetFocus()
        verify(fixture.scanner, never()).showPreview()
        assertFalse(capture.isCompleted)

        focusReset.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { capture.await() }

        verify(fixture.view(SECOND_VIEW_ID)).bindFocus()
        verify(fixture.scanner).showPreview()
    }

    @Test
    fun `new view applies only its crop when camera initialization selects it`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)

        fixture.session.setCropArea(FIRST_VIEW_ID, cropRect)
        clearInvocations(fixture.scanner)
        val second = fixture.attach(SECOND_VIEW_ID)

        verify(second, never()).setCropArea(cropRect)
        verify(fixture.scanner, never()).setCropArea(null)

        fixture.captureCamera(SECOND_VIEW_ID, null, null)

        verify(fixture.scanner).setCropArea(null)
    }

    @Test
    fun `new view applies its defaults instead of inheriting previous camera controls`() =
        runSessionTest {
            val fixture = Fixture()
            fixture.activateCamera(FIRST_VIEW_ID)
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.75F)
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
            fixture.session.setCropArea(
                FIRST_VIEW_ID,
                RecognizeVisorCropRect(scaleWidth = 0.5),
            )
            clearInvocations(fixture.scanner)

            fixture.attach(SECOND_VIEW_ID)
            fixture.captureCamera(SECOND_VIEW_ID, null, null)

            verify(fixture.scanner).setZoomRatio(1.0F)
            verify(fixture.scanner).setTorch(false)
            verify(fixture.scanner, atLeastOnce()).setCropArea(null)
            verify(fixture.scanner, never()).setZoomRatio(0.75F)
            verify(fixture.scanner, never()).setTorch(true)
        }

    @Test
    fun `view without initial controls restores camera defaults`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        fixture.captureCamera(SECOND_VIEW_ID, null, null)
        fixture.session.setZoomRatio(SECOND_VIEW_ID, 0.75F)
        fixture.session.toggleFlashLight(SECOND_VIEW_ID)
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(SECOND_VIEW_ID)
        fixture.captureCamera(FIRST_VIEW_ID, null, null)

        verify(fixture.scanner).setZoomRatio(1.0F)
        verify(fixture.scanner).setTorch(false)
        verify(fixture.scanner, never()).setZoomRatio(0.75F)
        verify(fixture.scanner, never()).setTorch(true)
    }

    @Test
    fun `capture restores selected view configuration`() = runSessionTest {
        val fixture = Fixture()
        val firstCrop = RecognizeVisorCropRect(scaleWidth = 0.4)
        val secondCrop = RecognizeVisorCropRect(scaleWidth = 0.7)
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.25F)
        fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        fixture.session.setCropArea(FIRST_VIEW_ID, firstCrop)
        fixture.session.startScan(FIRST_VIEW_ID, 100)

        fixture.attach(SECOND_VIEW_ID)
        fixture.captureCamera(SECOND_VIEW_ID, 0.75, secondCrop)
        fixture.session.startScan(SECOND_VIEW_ID, 200)
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(SECOND_VIEW_ID)

        verify(fixture.scanner, never()).setZoomRatio(0.25F)
        verify(fixture.scanner, never()).setTorch(true)
        verify(fixture.scanner, never()).setCropArea(firstCrop)
        verify(fixture.scanner, never()).updateScanPeriod(100)

        fixture.captureCamera(FIRST_VIEW_ID, null, null)

        verify(fixture.scanner).setZoomRatio(0.25F)
        verify(fixture.scanner).setTorch(true)
        verify(fixture.scanner).setCropArea(firstCrop)
        verify(fixture.scanner).updateScanPeriod(100)
        verify(fixture.scanner, never()).setCropArea(secondCrop)
        verify(fixture.scanner, never()).updateScanPeriod(200)
    }

    @Test
    fun `configuration commands for covered view are stored without changing current camera`() =
        runSessionTest {
            val fixture = Fixture()
            val updatedCrop = RecognizeVisorCropRect(scaleWidth = 0.35)
            fixture.activateCamera(FIRST_VIEW_ID)
            fixture.attach(SECOND_VIEW_ID)
            fixture.captureCamera(SECOND_VIEW_ID, null, null)
            clearInvocations(fixture.scanner)

            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.6F)
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
            fixture.session.setCropArea(FIRST_VIEW_ID, updatedCrop)
            fixture.session.updateScanPeriod(FIRST_VIEW_ID, 350)

            verify(fixture.scanner, never()).setZoomRatio(0.6F)
            verify(fixture.scanner, never()).setTorch(true)
            verify(fixture.scanner, never()).setCropArea(updatedCrop)
            verify(fixture.scanner, never()).updateScanPeriod(350)
            verify(fixture.view(FIRST_VIEW_ID)).setCropArea(updatedCrop)

            fixture.session.disposeView(SECOND_VIEW_ID)
            fixture.captureCamera(FIRST_VIEW_ID, null, null)

            verify(fixture.scanner).setZoomRatio(0.6F)
            verify(fixture.scanner).setTorch(true)
            verify(fixture.scanner).setCropArea(updatedCrop)
            verify(fixture.scanner).updateScanPeriod(350)
        }

    @Test
    fun `unsupported retained torch falls back to off and still reveals preview`() =
        runSessionTest {
            val fixture = Fixture()
            fixture.activateCamera(FIRST_VIEW_ID)
            fixture.attach(SECOND_VIEW_ID)
            fixture.captureCamera(SECOND_VIEW_ID, null, null)
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
            fixture.enqueueTorchResult(
                CompletableDeferred<Unit>().also {
                    it.completeExceptionally(PluginError.DeviceHasNotFlash)
                },
            )
            clearInvocations(fixture.scanner)

            fixture.session.disposeView(SECOND_VIEW_ID)
            fixture.captureCamera(FIRST_VIEW_ID, null, null)

            verify(fixture.scanner).setTorch(true)
            verify(fixture.scanner).setTorch(false)
            verify(fixture.scanner).showPreview()
        }

    @Test
    fun `preview host replacement cancels result captured by previous view`() {
        val received = mutableListOf<Pair<Int, Barcode>>()
        val fixture = Fixture { viewId, barcode -> received += viewId to barcode }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)

        fixture.emitScanResult(BARCODE)
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.postedCallbacks.single().run()

        assertTrue(received.isEmpty())
        verify(fixture.mainHandler).removeCallbacks(fixture.postedCallbacks.single())
    }

    @Test
    fun `only current view receives results when multiple views requested scanning`() {
        val received = mutableListOf<Pair<Int, Barcode>>()
        val fixture = Fixture { viewId, barcode -> received += viewId to barcode }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)

        fixture.emitScanResult(BARCODE)
        fixture.postedCallbacks.last().run()

        assertEquals(listOf(SECOND_VIEW_ID to BARCODE), received)

        fixture.session.disposeView(SECOND_VIEW_ID)
        runBlocking { fixture.captureCamera(FIRST_VIEW_ID, null, null) }
        fixture.markPreviewReady(FIRST_VIEW_ID)
        fixture.emitScanResult(BARCODE)
        fixture.postedCallbacks.last().run()

        assertEquals(
            listOf(SECOND_VIEW_ID to BARCODE, FIRST_VIEW_ID to BARCODE),
            received,
        )
    }

    @Test
    fun `lifecycle commands for disposed view do not affect current view`() {
        val fixture = Fixture()
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)
        fixture.session.disposeView(FIRST_VIEW_ID)
        clearInvocations(fixture.scanner)

        fixture.session.pauseCamera(FIRST_VIEW_ID)
        fixture.session.pauseScan(FIRST_VIEW_ID)

        assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
        verify(fixture.scanner, never()).pauseScan()
    }

    @Test
    fun `disposing one of several views keeps queued shared result`() {
        val received = mutableListOf<Barcode>()
        val fixture = Fixture { _, barcode -> received.add(barcode) }
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()

        fixture.session.disposeView(FIRST_VIEW_ID)
        delivery.run()

        assertEquals(listOf(BARCODE), received)
        verify(fixture.mainHandler, never()).removeCallbacks(delivery)
    }

    @Test
    fun `disposing preview host cancels result captured by that view`() {
        val received = mutableListOf<Pair<Int, Barcode>>()
        val fixture = Fixture { viewId, barcode -> received += viewId to barcode }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(SECOND_VIEW_ID)
        delivery.run()

        assertTrue(received.isEmpty())
        verify(fixture.mainHandler).removeCallbacks(delivery)
        verify(fixture.scanner, atLeastOnce()).pauseScan()

        verify(fixture.scanner, never()).resumeScan()
        fixture.markPreviewReady(FIRST_VIEW_ID)

        verify(fixture.scanner, never()).resumeScan()
        runBlocking { fixture.captureCamera(FIRST_VIEW_ID, null, null) }

        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `disposing one of several views does not pause shared scan`() {
        val fixture = Fixture()
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)
        fixture.session.startScan(SECOND_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(FIRST_VIEW_ID)

        verify(fixture.scanner, never()).pauseScan()
    }

    @Test
    fun `new view after navigation gap does not inherit removed view scan intent`() {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 100)
        clearInvocations(fixture.scanner)

        fixture.session.disposeView(FIRST_VIEW_ID)
        fixture.attach(SECOND_VIEW_ID)
        fixture.activateCamera(SECOND_VIEW_ID)

        verify(fixture.scanner, never()).resumeScan()
        verify(fixture.scanner, never()).updateScanPeriod(anyInt())
    }

    @Test
    fun `disposing last view cancels queued shared result`() {
        val received = mutableListOf<Barcode>()
        val fixture = Fixture { _, barcode -> received.add(barcode) }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()

        fixture.session.disposeView(FIRST_VIEW_ID)
        delivery.run()

        verify(fixture.mainHandler).removeCallbacks(delivery)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `camera pause cancels queued result and resume restores requested scan`() {
        val received = mutableListOf<Barcode>()
        val fixture = Fixture { _, barcode -> received.add(barcode) }
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.startScan(FIRST_VIEW_ID, 0)
        fixture.emitScanResult(BARCODE)
        val delivery = fixture.postedCallbacks.single()
        clearInvocations(fixture.scanner)

        fixture.session.pauseCamera(FIRST_VIEW_ID)
        delivery.run()

        verify(fixture.mainHandler).removeCallbacks(delivery)
        verify(fixture.scanner).pauseScan()
        verify(fixture.scanner, never()).hidePreview()
        assertTrue(received.isEmpty())

        clearInvocations(fixture.scanner)
        fixture.session.resumeCamera(FIRST_VIEW_ID)

        verify(fixture.scanner).showPreview()
        verify(fixture.scanner).resumeScan()
    }

    @Test
    fun `repeated camera initialization does not clear view pause`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        fixture.session.pauseCamera(FIRST_VIEW_ID)

        fixture.captureCamera(FIRST_VIEW_ID, null, null)

        assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
    }

    @Test
    fun `camera lifecycle stays created while session is inactive`() =
        runSessionTest {
            val fixture = Fixture(sessionActive = false)
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }

            assertEquals(Lifecycle.State.CREATED, fixture.session.lifecycle.currentState)
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            clearInvocations(fixture.scanner)
            fixture.session.startScan(FIRST_VIEW_ID, 0)
            verify(fixture.scanner).pauseScan()

            clearInvocations(fixture.scanner)
            fixture.session.activate()

            assertEquals(Lifecycle.State.RESUMED, fixture.session.lifecycle.currentState)
            verify(fixture.scanner).resumeScan()
        }

    @Test
    fun `session activation restores crop processing without an overlay redraw workaround`() =
        runSessionTest {
            val fixture = Fixture(sessionActive = false)
            val cropRect = RecognizeVisorCropRect(scaleWidth = 0.5)
            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, cropRect)
            }
            fixture.completeInitialization()
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            clearInvocations(fixture.scanner, fixture.view(FIRST_VIEW_ID))

            fixture.session.activate()

            verify(fixture.scanner).setCropArea(cropRect)
            verify(fixture.view(FIRST_VIEW_ID), never()).setCropArea(cropRect)
        }

    @Test
    fun `parallel starts share one camera initialization`() = runSessionTest {
        val fixture = Fixture()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }

        assertEquals(1, fixture.startCalls)
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        Unit
    }

    @Test
    fun `initialization completes only after bound camera opens`() = runSessionTest {
        val fixture = Fixture()
        val cameraOpen = CompletableDeferred<Unit>()
        fixture.enqueueOpenResult(cameraOpen)

        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, 0.5, null)
        }
        fixture.completeInitialization()

        verify(fixture.scanner, never()).setZoomRatio(0.5F)
        verify(fixture.scanner, never()).showPreview()
        assertFalse(initialization.isCompleted)

        cameraOpen.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }

        verify(fixture.scanner).setZoomRatio(0.5F)
        verify(fixture.scanner).showPreview()
    }

    @Test
    fun `camera open failure includes operation cause view and CameraX state code`() =
        runSessionTest {
            val fixture = Fixture()
            val cause = IllegalStateException("camera device disconnected")
            val cameraOpen = CompletableDeferred<Unit>()
            cameraOpen.completeExceptionally(
                PluginError.CameraControlError(
                    operation = CameraControlOperation.AWAIT_OPEN,
                    cause = cause,
                    cameraStateErrorCode = 4,
                ),
            )
            fixture.enqueueOpenResult(cameraOpen)

            val initialization = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.captureCamera(FIRST_VIEW_ID, null, null)
            }
            fixture.completeInitialization()

            val error = runCatching {
                withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
            }.exceptionOrNull()

            assertTrue(error is PluginError.CameraControlError)
            error as PluginError.CameraControlError
            assertEquals(CameraControlOperation.AWAIT_OPEN, error.operation)
            assertEquals(FIRST_VIEW_ID, error.viewId)
            assertEquals(4, error.cameraStateErrorCode)
            assertSame(cause, error.cause)
        }

    @Test
    fun `zoomRatio failure includes operation cause and view`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val cause = IllegalArgumentException("zoomRatio is unavailable")
        fixture.enqueueZoomResult(
            CompletableDeferred<Unit>().also {
                it.completeExceptionally(
                    PluginError.CameraControlError(
                        CameraControlOperation.ZOOM,
                        cause = cause,
                    ),
                )
            },
        )

        val error = runCatching {
            fixture.session.setZoomRatio(FIRST_VIEW_ID, 0.5F)
        }.exceptionOrNull()

        assertTrue(error is PluginError.CameraControlError)
        error as PluginError.CameraControlError
        assertEquals(CameraControlOperation.ZOOM, error.operation)
        assertEquals(FIRST_VIEW_ID, error.viewId)
        assertSame(cause, error.cause)
    }

    @Test
    fun `torch failure includes operation cause and view`() = runSessionTest {
        val fixture = Fixture()
        fixture.activateCamera(FIRST_VIEW_ID)
        val cause = IllegalStateException("torch is unavailable")
        fixture.enqueueTorchResult(
            CompletableDeferred<Unit>().also {
                it.completeExceptionally(
                    PluginError.CameraControlError(
                        CameraControlOperation.TORCH,
                        cause = cause,
                    ),
                )
            },
        )

        val error = runCatching {
            fixture.session.toggleFlashLight(FIRST_VIEW_ID)
        }.exceptionOrNull()

        assertTrue(error is PluginError.CameraControlError)
        error as PluginError.CameraControlError
        assertEquals(CameraControlOperation.TORCH, error.operation)
        assertEquals(FIRST_VIEW_ID, error.viewId)
        assertSame(cause, error.cause)
    }

    @Test
    fun `parallel captures use first registration state and newest execution`() = runSessionTest {
        val fixture = Fixture()
        val firstCrop = RecognizeVisorCropRect(scaleWidth = 0.25)
        val secondCrop = RecognizeVisorCropRect(scaleWidth = 0.75)
        val zoomCompletion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(zoomCompletion)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(
                FIRST_VIEW_ID,
                0.25,
                firstCrop,
            )
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(
                FIRST_VIEW_ID,
                0.75,
                secondCrop,
            )
        }

        assertEquals(1, fixture.startCalls)
        verify(fixture.scanner, never()).setCropArea(anyValue())
        verify(fixture.scanner, never()).setCropArea(secondCrop)
        verify(fixture.scanner, never()).setZoomRatio(anyFloat())

        fixture.completeInitialization()
        yield()
        verify(fixture.scanner).setZoomRatio(0.25F)
        verify(fixture.scanner, never()).showPreview()
        assertTrue(first.isCompleted)
        assertFalse(second.isCompleted)

        zoomCompletion.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        verify(fixture.scanner).showPreview()
        Unit
    }

    @Test
    fun `recapture ignores new creation values and reapplies retained configuration`() =
        runSessionTest {
        val fixture = Fixture()
        val firstCrop = RecognizeVisorCropRect(scaleWidth = 0.25)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(
                FIRST_VIEW_ID,
                0.25,
                firstCrop,
            )
        }
        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        clearInvocations(fixture.scanner)

        fixture.captureCamera(
            FIRST_VIEW_ID,
            0.75,
            RecognizeVisorCropRect(scaleWidth = 0.75),
        )

        assertEquals(1, fixture.startCalls)
        verify(fixture.scanner).setCropArea(firstCrop)
        verify(fixture.scanner).setZoomRatio(0.25F)
        verify(fixture.scanner, never()).setCropArea(
            RecognizeVisorCropRect(scaleWidth = 0.75),
        )
        verify(fixture.scanner, never()).setZoomRatio(0.75F)
        }

    @Test
    fun `release fails pending initialization and disposes resources once`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }

        fixture.session.release()
        fixture.session.release()

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
        verify(fixture.scanner).dispose()
        assertEquals(1, fixture.subscriptionCancelCalls)
        assertEquals(1, fixture.releaseCalls)
    }

    @Test
    fun `release detaches session owner before actor teardown`() {
        val scanner = mock(Scanner::class.java)
        val queuedTasks = ArrayDeque<Runnable>()
        val queuedDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queuedTasks += block
            }
        }
        var releasedSession: ScannerSession? = null
        val session = ScannerSessionImpl(
            scanner = scanner,
            mainHandler = mock(Handler::class.java),
            onScanResult = { _, _ -> },
            onReleaseRequested = { releasedSession = it },
            initializationScope = CoroutineScope(queuedDispatcher),
            lifecycleRegistryFactory = LifecycleRegistry::createUnsafe,
        )

        session.release()

        assertSame(session, releasedSession)
        verify(scanner, never()).dispose()

        queuedTasks.removeFirst().run()
        verify(scanner).dispose()
    }

    @Test
    fun `initialization error releases session and is propagated`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }
        val expectedError = IllegalStateException("initialization failed")

        fixture.failInitialization(expectedError)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(expectedError.message, error?.message)
        verify(fixture.scanner).dispose()
    }

    @Test
    fun `initial zoomRatio error is typed and keeps preview hidden`() = runSessionTest {
        val fixture = Fixture()
        val zoomCompletion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(zoomCompletion)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, 0.5, null)
        }
        val expectedError = IllegalStateException("zoomRatio failed")
        fixture.completeInitialization()

        zoomCompletion.completeExceptionally(expectedError)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertTrue(error is PluginError.CameraControlError)
        error as PluginError.CameraControlError
        assertEquals(CameraControlOperation.ZOOM, error.operation)
        assertEquals(FIRST_VIEW_ID, error.viewId)
        assertTrue(error.cause is IllegalStateException)
        assertEquals(expectedError.message, error.cause?.message)
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `unsupported initial torch remains an initialization error`() = runSessionTest {
        val fixture = Fixture()
        fixture.enqueueTorchResult(
            CompletableDeferred<Unit>().also {
                it.completeExceptionally(PluginError.DeviceHasNotFlash)
            },
        )
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(
                FIRST_VIEW_ID,
                initialZoomRatio = null,
                initialCropRect = null,
                initialFlashEnabled = true,
            )
        }

        fixture.completeInitialization()

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertSame(PluginError.DeviceHasNotFlash, error)
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner, never()).dispose()
    }

    @Test
    fun `release while initial zoomRatio is pending keeps preview hidden`() = runSessionTest {
        val fixture = Fixture()
        val zoomCompletion = CompletableDeferred<Unit>()
        fixture.enqueueZoomResult(zoomCompletion)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.captureCamera(FIRST_VIEW_ID, 0.5, null)
        }
        fixture.completeInitialization()

        fixture.session.release()
        zoomCompletion.complete(Unit)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
        verify(fixture.scanner, never()).showPreview()
        verify(fixture.scanner).dispose()
    }

    @Test
    fun `capture arriving after release is canceled without restarting camera`() = runSessionTest {
        val fixture = Fixture()
        fixture.session.release()

        val error = runCatching {
            fixture.captureCamera(FIRST_VIEW_ID, null, null)
        }.exceptionOrNull()

        assertEquals(null, error)
        assertEquals(0, fixture.startCalls)
    }

    private fun runSessionTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { supervisorScope { block() } }
    }

    private class Fixture(
        sessionActive: Boolean = true,
        onScanResult: (Int, Barcode) -> Unit = { _, _ -> },
    ) {
        val scanner: Scanner = mock(Scanner::class.java)
        val mainHandler: Handler = mock(Handler::class.java)
        val postedCallbacks = mutableListOf<Runnable>()
        val delayedCallbacks = mutableListOf<Runnable>()
        val scheduledDelays = mutableListOf<Long>()
        val session: ScannerSessionImpl
        var startCalls = 0
            private set
        var subscriptionCancelCalls = 0
            private set
        var releaseCalls = 0
            private set

        private val views = mutableMapOf<Int, ScannerView>()
        private val configuredViews = mutableSetOf<Int>()
        private val previewState = mutableMapOf<Int, Boolean>()
        private val previewReadyState = mutableMapOf<Int, Boolean>()
        private val previewShouldBeReadyState = mutableMapOf<Int, Boolean>()
        private val previewReadyCallbacks = mutableMapOf<Int, () -> Unit>()
        private var onReady: (() -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null
        private var onAvailabilityChanged: ((CameraAvailability) -> Unit)? = null
        private var scanResultListener: ((Barcode) -> Unit)? = null
        private val openResults = ArrayDeque<CompletableDeferred<Unit>>()
        private val focusResetResults = ArrayDeque<CompletableDeferred<Unit>>()
        private val focusResults = ArrayDeque<CompletableDeferred<Unit>>()
        private val zoomResults = ArrayDeque<CompletableDeferred<Unit>>()
        private val torchResults = ArrayDeque<CompletableDeferred<Unit>>()

        init {
            doAnswer { invocation ->
                postedCallbacks += invocation.getArgument<Runnable>(0)
                true
            }.`when`(mainHandler).post(anyValue())
            doAnswer { invocation ->
                delayedCallbacks += invocation.getArgument<Runnable>(0)
                scheduledDelays += invocation.getArgument<Long>(1)
                true
            }.`when`(mainHandler).postDelayed(anyValue(), anyLong())
            doAnswer { invocation ->
                scanResultListener = invocation.getArgument(0)
                ScanResultSubscription { subscriptionCancelCalls += 1 }
            }.`when`(scanner).subscribeToScanResults(anyValue())
            doAnswer { invocation ->
                startCalls += 1
                onAvailabilityChanged = invocation.getArgument(1)
                onReady = invocation.getArgument(2)
                onError = invocation.getArgument(3)
                null
            }.`when`(scanner).startCamera(anyValue(), anyValue(), anyValue(), anyValue())
            doAnswer {
                focusResetResults.removeFirstOrNull() ?: CompletableDeferred(Unit)
            }.`when`(scanner).resetFocus()
            doAnswer {
                focusResults.removeFirstOrNull() ?: CompletableDeferred(Unit)
            }.`when`(scanner).focusOnCenter(anyLong(), anyFloat(), anyFloat())
            doAnswer {
                zoomResults.removeFirstOrNull() ?: CompletableDeferred(Unit)
            }.`when`(scanner).setZoomRatio(anyFloat())
            doAnswer {
                torchResults.removeFirstOrNull() ?: CompletableDeferred(Unit)
            }.`when`(scanner).setTorch(anyBoolean())
            doAnswer {
                null
            }.`when`(scanner).dispose()

            session = ScannerSessionImpl(
                scanner = scanner,
                mainHandler = mainHandler,
                onScanResult = onScanResult,
                onReleaseRequested = { releaseCalls += 1 },
                initializationScope = CoroutineScope(Dispatchers.Unconfined),
                lifecycleRegistryFactory = LifecycleRegistry::createUnsafe,
            )
            if (sessionActive) session.activate() else session.deactivate()
            attach(FIRST_VIEW_ID)
        }

        fun attach(
            viewId: Int,
            previewReady: Boolean = true,
            initialZoomRatio: Double? = null,
            initialCropRect: RecognizeVisorCropRect? = null,
            initialFlashEnabled: Boolean? = null,
        ): ScannerView {
            val view = mock(ScannerView::class.java)
            previewState[viewId] = false
            previewReadyState[viewId] = false
            previewShouldBeReadyState[viewId] = previewReady
            doAnswer { invocation ->
                previewState.keys.forEach { currentViewId ->
                    previewState[currentViewId] = false
                    previewReadyState[currentViewId] = false
                }
                previewState[viewId] = true
                previewReadyState[viewId] = false
                previewReadyCallbacks[viewId] = invocation.getArgument(0)
                if (previewShouldBeReadyState[viewId] == true) markPreviewReady(viewId)
                null
            }.`when`(view).attachPreview(anyValue())
            doAnswer {
                clearPreview(viewId)
                null
            }.`when`(view).detachPreview()
            doAnswer {
                clearPreview(viewId)
                null
            }.`when`(view).disposeFromSession()
            doAnswer { previewState[viewId] == true }.`when`(view).hasPreview()
            doAnswer {
                previewState[viewId] == true && previewReadyState[viewId] == true
            }.`when`(view).isPreviewReady()
            views[viewId] = view
            session.attachView(
                viewId = viewId,
                view = view,
                initialZoomRatio = initialZoomRatio,
                initialCropRect = initialCropRect,
                initialFlashEnabled = initialFlashEnabled,
            )
            return view
        }

        suspend fun captureCamera(
            viewId: Int,
            initialZoomRatio: Double?,
            initialCropRect: RecognizeVisorCropRect?,
            initialFlashEnabled: Boolean? = null,
        ) {
            if (configuredViews.add(viewId)) {
                if (
                    initialZoomRatio != null ||
                    initialCropRect != null ||
                    initialFlashEnabled != null
                ) {
                    session.disposeView(viewId)
                    session.attachView(
                        viewId = viewId,
                        view = view(viewId),
                        initialZoomRatio = initialZoomRatio,
                        initialCropRect = initialCropRect,
                        initialFlashEnabled = initialFlashEnabled,
                    )
                }
            }
            session.captureCamera(viewId) { true }
        }

        fun markPreviewReady(viewId: Int) {
            previewShouldBeReadyState[viewId] = true
            previewReadyState[viewId] = true
            previewReadyCallbacks.remove(viewId)?.invoke()
        }

        fun view(viewId: Int): ScannerView = views.getValue(viewId)

        fun hasPreview(viewId: Int): Boolean = previewState[viewId] == true

        private fun clearPreview(viewId: Int) {
            previewState[viewId] = false
            previewReadyState[viewId] = false
            previewReadyCallbacks.remove(viewId)
        }

        fun emitScanResult(result: Barcode) {
            scanResultListener?.invoke(result)
        }

        fun emitCameraClosed() {
            onAvailabilityChanged?.invoke(CameraAvailability.Closed())
        }

        fun emitCameraOpen() {
            onAvailabilityChanged?.invoke(CameraAvailability.Open)
        }

        fun activateCamera(viewId: Int) {
            runBlocking {
                val capture = async(start = CoroutineStart.UNDISPATCHED) {
                    captureCamera(viewId, null, null)
                }
                completeInitialization()
                capture.await()
            }
        }

        suspend fun completeInitialization() {
            // Let captures resumed by permission or another view reach startCamera first, then
            // flush continuations resumed by the native initialization callback.
            yield()
            onReady?.invoke()
            val controlledOpen = openResults.removeFirstOrNull()
            if (controlledOpen == null) {
                onAvailabilityChanged?.invoke(CameraAvailability.Open)
            } else {
                observeOpenResult(controlledOpen)
            }
            yield()
        }

        fun enqueueZoomResult(result: CompletableDeferred<Unit>) {
            zoomResults += result
        }

        fun enqueueFocusReset(result: CompletableDeferred<Unit>) {
            focusResetResults += result
        }

        fun enqueueFocusResult(result: CompletableDeferred<Unit>) {
            focusResults += result
        }

        fun enqueueOpenResult(result: CompletableDeferred<Unit>) {
            if (onAvailabilityChanged == null) {
                openResults += result
            } else {
                onAvailabilityChanged?.invoke(CameraAvailability.Closed())
                observeOpenResult(result)
            }
        }

        private fun observeOpenResult(result: CompletableDeferred<Unit>) {
            result.invokeOnCompletion { error ->
                val cameraError = error as? PluginError.CameraControlError
                if (error == null) {
                    onAvailabilityChanged?.invoke(CameraAvailability.Open)
                } else {
                    onAvailabilityChanged?.invoke(
                        CameraAvailability.Closed(
                            errorCode = cameraError?.cameraStateErrorCode,
                            cause = cameraError?.cause ?: error,
                        ),
                    )
                }
            }
        }

        fun failInitialization(error: Exception) {
            onError?.invoke(error)
        }

        fun enqueueTorchResult(result: CompletableDeferred<Unit>) {
            torchResults += result
        }
    }

    private companion object {
        const val FIRST_VIEW_ID = 11
        const val SECOND_VIEW_ID = 22
        const val THIRD_VIEW_ID = 33
        const val TEST_TIMEOUT_MS = 1_000L
        val BARCODE = Barcode(
            rawValue = "1234567890",
            displayValue = "1234567890",
            format = 1,
            valueType = 1,
        )

        fun <T> anyValue(): T = any<T>()
    }
}
