package com.dns_technologies.mlkit_scanner.permissions

import android.app.Activity
import android.content.pm.PackageManager
import android.Manifest
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.Test
import org.mockito.Mockito.mock
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// region PermissionGatewayTest
internal class PermissionGatewayTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    @Test
    fun `blank permission names are rejected before checking or dispatch`() = gatewayTest { fixture ->
        for (permission in listOf("", " ", "\t")) {
            val failure = runCatching {
                fixture.gateway.requestPermission(permission, requestCode = 1)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        assertTrue(fixture.checkedActivities.isEmpty())
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test
    fun `non camera permission is dispatched and granted independently of camera`() = gatewayTest { fixture ->
        val result = request(fixture, AUDIO)
        assertEquals(AUDIO, fixture.dispatched.single().permission)
        fixture.grantedPermissions += AUDIO
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
        assertTrue(result.await())
    }

    @Test
    fun `already granted non camera permission does not open a dialog`() = gatewayTest { fixture ->
        fixture.grantedPermissions += AUDIO
        assertTrue(fixture.gateway.requestPermission(AUDIO, requestCode = 1))
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test
    fun `same non camera permission shares one request including denial`() = gatewayTest { fixture ->
        val first = request(fixture, AUDIO)
        val second = request(fixture, AUDIO)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertFalse(second.await())
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun `different permission waits for its own request and never shares another result`() = gatewayTest { fixture ->
        val first = request(fixture)
        val second = request(fixture, AUDIO)
        assertFalse(second.isCompleted)
        assertEquals(1, fixture.dispatched.size)
        fixture.grantedPermissions += CAMERA
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
        assertTrue(first.await())
        assertEquals(listOf(CAMERA, AUDIO), fixture.dispatched.map { it.permission })
        assertFalse(second.isCompleted)
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(second.await())
    }

    @Test
    fun `equivalent queued permissions share their eventual dialog and denial`() = gatewayTest { fixture ->
        val first = request(fixture)
        val waiting = List(32) { request(fixture, AUDIO) }
        assertEquals(1, fixture.dispatched.size)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertEquals(listOf(CAMERA, AUDIO), fixture.dispatched.map { it.permission })
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        waiting.forEach { assertFalse(it.await()) }
        assertEquals(2, fixture.dispatched.size)
    }

    @Test
    fun `different permissions are dispatched in queue order`() = gatewayTest { fixture ->
        val first = request(fixture)
        val second = request(fixture, AUDIO)
        val third = request(fixture, LOCATION)
        for (result in listOf(first, second, third)) {
            assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
            assertFalse(result.await())
        }
        assertEquals(listOf(CAMERA, AUDIO, LOCATION), fixture.dispatched.map { it.permission })
    }

    @Test
    fun `joining a queued permission preserves its position before later requests`() = gatewayTest { fixture ->
        val first = request(fixture)
        val second = request(fixture, AUDIO)
        val third = request(fixture, LOCATION)
        val joined = request(fixture, AUDIO)

        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertEquals(AUDIO, fixture.dispatched.last().permission)
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(second.await())
        assertFalse(joined.await())
        assertEquals(LOCATION, fixture.dispatched.last().permission)
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(third.await())
        assertEquals(3, fixture.dispatched.size)
    }

    @Test
    fun `retrying a cancelled queued permission appends it after remaining requests`() = gatewayTest { fixture ->
        val first = request(fixture)
        val cancelled = request(fixture, AUDIO)
        val second = request(fixture, LOCATION)
        cancelled.cancelAndJoin()
        val retry = request(fixture, AUDIO)

        for (result in listOf(first, second, retry)) {
            assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
            assertFalse(result.await())
        }
        assertEquals(listOf(CAMERA, LOCATION, AUDIO), fixture.dispatched.map { it.permission })
    }

    @Test
    fun `queued request rechecks grants before opening a dialog`() = gatewayTest { fixture ->
        val first = request(fixture)
        val second = request(fixture, AUDIO)
        fixture.grantedPermissions += AUDIO
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertTrue(second.await())
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun `cancelled queued caller cannot trigger dispatch before its finally runs`() = gatewayTest { fixture ->
        val first = request(fixture)
        val cancelled = request(fixture, AUDIO)
        cancelled.cancel()
        // Do not yield or join: dispatch must inspect the caller Job, not await its cleanup.
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertEquals(1, fixture.dispatched.size)
        assertFalse(first.await())
        cancelled.join()
    }

    @Test
    fun `one cancelled queued caller does not discard another callers request`() = gatewayTest { fixture ->
        val first = request(fixture)
        val cancelled = request(fixture, AUDIO)
        val remaining = request(fixture, AUDIO)
        cancelled.cancelAndJoin()
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        fixture.grantedPermissions += AUDIO
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = true))
        assertTrue(remaining.await())
        assertEquals(2, fixture.dispatched.size)
    }

    @Test
    fun `configuration detach preserves queue without starting its next request`() = gatewayTest { fixture ->
        val first = request(fixture)
        val second = request(fixture, AUDIO)
        fixture.gateway.detachForConfigChange()
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertEquals(1, fixture.dispatched.size)
        assertFalse(second.isCompleted)
        fixture.gateway.attach(fixture.secondActivity)
        assertEquals(AUDIO, fixture.dispatched.last().permission)
        assertSame(fixture.secondActivity, fixture.dispatched.last().activity)
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(second.await())
    }

    @Test
    fun `final detach completes active and queued requests without restarting on reattach`() = gatewayTest { fixture ->
        val first = request(fixture)
        val second = request(fixture, AUDIO)
        val third = request(fixture, LOCATION)
        fixture.gateway.detachFinal()
        fixture.gateway.attach(fixture.secondActivity)
        assertFalse(first.await())
        assertFalse(second.await())
        assertFalse(third.await())
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun `reentrant final detach during completion discards the old queue`() = gatewayTest { fixture ->
        val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            val granted = fixture.gateway.requestCameraPermission()
            fixture.gateway.detachFinal()
            fixture.gateway.attach(fixture.secondActivity)
            granted
        }
        val second = request(fixture, AUDIO)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertFalse(second.await())
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun `different already granted permission succeeds without disturbing the pending request`() =
        gatewayTest { fixture ->
            val first = request(fixture)
            fixture.grantedPermissions += AUDIO
            assertTrue(fixture.gateway.requestPermission(AUDIO, requestCode = 1))
            assertFalse(first.isCompleted)
            assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
            assertFalse(first.await())
        }

    @Test
    fun `different permission can be requested after the previous one completes`() = gatewayTest { fixture ->
        val first = request(fixture)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        val second = request(fixture, AUDIO)
        assertEquals(listOf(CAMERA, AUDIO), fixture.dispatched.map { it.permission })
        assertEquals(1, fixture.dispatched.last().requestCode)
        fixture.grantedPermissions += AUDIO
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = true))
        assertTrue(second.await())
    }

    @Test
    fun `non camera callback can complete its owned request during configuration detach`() = gatewayTest { fixture ->
        val result = request(fixture, AUDIO)
        fixture.gateway.detachForConfigChange()
        fixture.checkedActivities.clear()
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
        assertTrue(result.await())
        assertTrue(fixture.checkedActivities.isEmpty())
    }

    @Test
    fun `reattach checks the pending permission rather than camera`() = gatewayTest { fixture ->
        val result = request(fixture, AUDIO)
        fixture.gateway.detachForConfigChange()
        fixture.grantedPermissions += AUDIO
        fixture.gateway.attach(fixture.secondActivity)
        assertTrue(result.await())
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun `camera callback cannot complete a non camera request with the same code`() = gatewayTest { fixture ->
        val result = request(fixture, AUDIO)
        val request = fixture.dispatched.single()
        assertFalse(fixture.gateway.onPermissionResult(request.requestCode, arrayOf(CAMERA), intArrayOf(GRANTED)))
        assertFalse(result.isCompleted)
        assertTrue(fixture.complete(request, isGranted = false))
        assertFalse(result.await())
    }

    @Test
    fun `camera uses fixed code zero on every request`() = gatewayTest { fixture ->
        repeat(3) {
            val result = request(fixture)
            assertEquals(0, fixture.dispatched.last().requestCode)
            assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
            assertFalse(result.await())
        }
    }

    @Test
    fun `fixed codes do not depend on queue position or completed requests`() = gatewayTest { fixture ->
        val first = request(fixture, AUDIO)
        assertEquals(1, fixture.dispatched.single().requestCode)
        val second = request(fixture)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertEquals(0, fixture.dispatched.last().requestCode)
        val third = request(fixture, LOCATION)
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(second.await())
        assertEquals(2, fixture.dispatched.last().requestCode)
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(third.await())
    }

    @Test
    fun `callback for queued but undispatched request is not consumed`() = gatewayTest { fixture ->
        val first = request(fixture)
        val second = request(fixture, AUDIO)
        assertFalse(fixture.gateway.onPermissionResult(1, arrayOf(AUDIO), intArrayOf(GRANTED)))
        assertFalse(second.isCompleted)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(first.await())
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(second.await())
    }

    @Test
    fun `each permission keeps its fixed code even when the queue never empties`() = gatewayTest { fixture ->
        var current = request(fixture)
        var next = request(fixture, AUDIO)
        repeat(1_000) { index ->
            assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
            assertFalse(current.await())
            current = next
            next = request(fixture, if (index % 2 == 0) CAMERA else AUDIO)
            val dispatched = fixture.dispatched.last()
            assertEquals(if (dispatched.permission == CAMERA) 0 else 1, dispatched.requestCode)
        }
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(current.await())
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(next.await())
    }

    @Test
    fun `cancelled queued objects cleanup cannot remove a replacement with the same permission`() =
        gatewayTest { fixture ->
            val first = request(fixture)
            val cancelled = request(fixture, AUDIO)
            cancelled.cancel()
            assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
            val replacement = request(fixture, AUDIO)
            cancelled.join()
            assertFalse(first.await())
            assertFalse(replacement.isCompleted)
            assertEquals(2, fixture.dispatched.size)
            assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
            assertFalse(replacement.await())
        }

    @Test
    fun `final detach snapshot cannot complete a new request created by a resumed caller`() = gatewayTest { fixture ->
        val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            assertFalse(fixture.gateway.requestCameraPermission())
            fixture.gateway.attach(fixture.secondActivity)
            fixture.gateway.requestPermission(AUDIO, requestCode = 1)
        }
        val oldQueued = request(fixture, AUDIO)
        fixture.gateway.detachFinal()
        assertFalse(oldQueued.await())
        assertFalse(first.isCompleted)
        assertEquals(2, fixture.dispatched.size)
        fixture.grantedPermissions += AUDIO
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = true))
        assertTrue(first.await())
    }

    @Test
    fun `already granted camera permission does not open a dialog`() = gatewayTest { fixture ->
        fixture.isGranted = true
        assertTrue(fixture.gateway.requestCameraPermission())
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test
    fun `request without an Activity is denied without dispatch`() = gatewayTest { fixture ->
        fixture.gateway.detachFinal()
        assertFalse(fixture.gateway.requestCameraPermission())
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test
    fun `permission is checked again after a previous grant`() = gatewayTest { fixture ->
        fixture.isGranted = true
        assertTrue(fixture.gateway.requestCameraPermission())
        fixture.isGranted = false
        val result = request(fixture)
        assertEquals(1, fixture.dispatched.size)
        assertFalse(result.isCompleted)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertFalse(result.await())
    }

    @Test
    fun `concurrent callers share one dialog and one result`() = gatewayTest { fixture ->
        val results = List(32) { request(fixture) }
        assertEquals(1, fixture.dispatched.size)
        fixture.isGranted = true
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
        results.forEach { assertTrue(it.await()) }
    }

    @Test
    fun `cancelling one caller does not cancel another caller`() = gatewayTest { fixture ->
        val cancelled = request(fixture)
        val remaining = request(fixture)
        cancelled.cancelAndJoin()
        fixture.isGranted = true
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
        assertTrue(remaining.await())
        assertTrue(cancelled.isCancelled)
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun `cancelling every caller keeps the open dialog available to a later caller`() =
        gatewayTest { fixture ->
            val first = request(fixture)
            val second = request(fixture)
            first.cancelAndJoin()
            second.cancelAndJoin()
            val next = request(fixture)
            assertEquals(1, fixture.dispatched.size)
            fixture.isGranted = true
            assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
            assertTrue(next.await())
        }

    @Test
    fun `callback with no remaining callers releases the dialog for a retry`() =
        gatewayTest { fixture ->
            val first = request(fixture)
            first.cancelAndJoin()
            assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
            val next = request(fixture)
            assertEquals(2, fixture.dispatched.size)
            assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
            assertFalse(next.await())
        }

    @Test
    fun `already cancelled caller cannot return granted from fast path`() = gatewayTest { fixture ->
        fixture.isGranted = true
        var returned = false
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            fixture.gateway.requestCameraPermission()
            returned = true
        }
        result.join()
        assertFalse(returned)
        assertTrue(fixture.checkedActivities.isEmpty())
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test
    fun `already cancelled caller cannot open a dialog`() = gatewayTest { fixture ->
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            fixture.gateway.requestCameraPermission()
        }
        result.join()
        assertTrue(result.isCancelled)
        assertTrue(fixture.checkedActivities.isEmpty())
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test
    fun `configuration detach preserves active request until callback after reattach`() =
        gatewayTest { fixture ->
            val result = request(fixture)
            val dispatched = fixture.dispatched.single()
            fixture.gateway.detachForConfigChange()
            assertFalse(result.isCompleted)
            fixture.gateway.attach(fixture.secondActivity)
            assertFalse(result.isCompleted)
            fixture.isGranted = true
            assertTrue(fixture.complete(dispatched, isGranted = true))
            assertTrue(result.await())
            assertEquals(1, fixture.dispatched.size)
            assertSame(fixture.secondActivity, fixture.checkedActivities.last())
        }

    @Test
    fun `matching callback can grant active request during configuration detach`() =
        gatewayTest { fixture ->
            val result = request(fixture)
            fixture.gateway.detachForConfigChange()
            fixture.checkedActivities.clear()
            assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
            assertTrue(result.await())
            assertTrue(fixture.checkedActivities.isEmpty())
        }

    @Test
    fun `matching callback can deny active request during configuration detach`() =
        gatewayTest { fixture ->
            val result = request(fixture)
            fixture.gateway.detachForConfigChange()
            assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
            assertFalse(result.await())
        }

    @Test
    fun `caller can join an existing dialog during configuration detach`() = gatewayTest { fixture ->
        val first = request(fixture)
        fixture.gateway.detachForConfigChange()
        val second = request(fixture)
        assertFalse(second.isCompleted)
        assertEquals(1, fixture.dispatched.size)
        fixture.gateway.attach(fixture.secondActivity)
        fixture.isGranted = true
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
        assertTrue(first.await())
        assertTrue(second.await())
    }

    @Test
    fun `configuration detach without a pending dialog cannot dispatch`() = gatewayTest { fixture ->
        fixture.gateway.detachForConfigChange()
        assertFalse(fixture.gateway.requestCameraPermission())
        fixture.gateway.attach(fixture.secondActivity)
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test
    fun `reattach completes active request when permission is already granted`() =
        gatewayTest { fixture ->
            val result = request(fixture)
            val dispatched = fixture.dispatched.single()
            fixture.gateway.detachForConfigChange()
            fixture.isGranted = true
            fixture.gateway.attach(fixture.secondActivity)
            assertTrue(result.await())
            assertEquals(1, fixture.dispatched.size)
            assertFalse(fixture.complete(dispatched, isGranted = true))
        }

    @Test
    fun `final detach completes all callers and repeated detach is harmless`() =
        gatewayTest { fixture ->
            val first = request(fixture)
            val second = request(fixture)
            val dispatched = fixture.dispatched.single()
            fixture.gateway.detachForConfigChange()
            fixture.gateway.detachFinal()
            fixture.gateway.detachFinal()
            assertFalse(first.await())
            assertFalse(second.await())
            assertFalse(fixture.complete(dispatched, isGranted = true))
            fixture.gateway.attach(fixture.secondActivity)
            val next = request(fixture)
            assertSame(fixture.secondActivity, fixture.dispatched.last().activity)
            assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
            assertFalse(next.await())
        }

    @Test
    fun `callback after completion with no pending owner is ignored`() = gatewayTest { fixture ->
        val result = request(fixture)
        val dispatched = fixture.dispatched.single()
        assertTrue(fixture.complete(dispatched, isGranted = false))
        assertFalse(result.await())
        assertFalse(fixture.complete(dispatched, isGranted = false))
    }

    @Test
    fun `late callback after final detach cannot complete a different permission`() = gatewayTest { fixture ->
        val first = request(fixture)
        val oldRequest = fixture.dispatched.single()
        fixture.gateway.detachFinal()
        assertFalse(first.await())
        fixture.gateway.attach(fixture.secondActivity)
        val next = request(fixture, AUDIO)
        assertFalse(fixture.complete(oldRequest, isGranted = false))
        assertFalse(next.isCompleted)
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
        assertFalse(next.await())
    }

    @Test
    fun `callback for a request completed on reattach is ignored without a pending owner`() =
        gatewayTest { fixture ->
            val result = request(fixture)
            val oldRequest = fixture.dispatched.single()
            fixture.gateway.detachForConfigChange()
            fixture.isGranted = true
            fixture.gateway.attach(fixture.secondActivity)
            assertTrue(result.await())
            assertFalse(fixture.complete(oldRequest, isGranted = true))
        }

    @Test
    fun `separate Activity hosts may use the same code for their own requests`() = gatewayTest { firstFixture ->
        val secondFixture = Fixture()
        try {
            val first = request(firstFixture)
            val second = request(secondFixture)
            assertEquals(0, firstFixture.dispatched.single().requestCode)
            assertEquals(0, secondFixture.dispatched.single().requestCode)
            firstFixture.isGranted = true
            assertTrue(firstFixture.complete(firstFixture.dispatched.single(), isGranted = true))
            assertTrue(first.await())
            assertFalse(second.isCompleted)
            assertTrue(secondFixture.complete(secondFixture.dispatched.single(), isGranted = false))
            assertFalse(second.await())
        } finally {
            secondFixture.gateway.detachFinal()
        }
    }

    @Test
    fun `more than 65536 completed requests do not exhaust codes`() = runBlocking {
        lateinit var gateway: PermissionGateway
        var dispatches = 0
        gateway = PermissionGateway(
            permissionChecker = { _, _ -> false },
            permissionRequester = { _, permission, code ->
                assertEquals(0, code)
                dispatches++
                assertTrue(gateway.onPermissionResult(
                    code, arrayOf(permission), intArrayOf(PackageManager.PERMISSION_DENIED),
                ))
            },
        )
        gateway.attach(mock(Activity::class.java))
        try {
            repeat(65_537) { assertFalse(gateway.requestCameraPermission()) }
            assertEquals(65_537, dispatches)
        } finally {
            gateway.detachFinal()
        }
    }

    @Test
    fun `unrelated request code and permission set are not consumed`() = gatewayTest { fixture ->
        val result = request(fixture)
        val dispatched = fixture.dispatched.single()
        assertFalse(
            fixture.gateway.onPermissionResult(
                dispatched.requestCode + 1, arrayOf(CAMERA), intArrayOf(GRANTED),
            ),
        )
        assertFalse(
            fixture.gateway.onPermissionResult(
                dispatched.requestCode, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(GRANTED),
            ),
        )
        assertFalse(
            fixture.gateway.onPermissionResult(
                dispatched.requestCode, arrayOf(CAMERA, CAMERA), intArrayOf(GRANTED, GRANTED),
            ),
        )
        assertFalse(result.isCompleted)
        assertTrue(fixture.complete(dispatched, isGranted = false))
        assertFalse(result.await())
    }

    @Test
    fun `callback without an active request is not consumed`() = gatewayTest { fixture ->
        assertFalse(fixture.gateway.onPermissionResult(0, arrayOf(CAMERA), intArrayOf(GRANTED)))
    }

    @Test
    fun `interrupted permission dialog completes active request as denied`() =
        gatewayTest { fixture ->
            val result = request(fixture)
            fixture.isGranted = true
            assertTrue(
                fixture.gateway.onPermissionResult(
                    fixture.dispatched.single().requestCode, emptyArray(), intArrayOf(),
                ),
            )
            assertFalse(result.await())
        }

    @Test
    fun `malformed result is denied even when permission checker reports granted`() =
        gatewayTest { fixture ->
            for (grantResults in listOf(intArrayOf(), intArrayOf(GRANTED, GRANTED), intArrayOf(42))) {
                fixture.isGranted = false
                val result = request(fixture)
                fixture.isGranted = true
                assertTrue(
                    fixture.gateway.onPermissionResult(
                        fixture.dispatched.last().requestCode, arrayOf(CAMERA), grantResults,
                    ),
                )
                assertFalse(result.await())
            }
        }

    @Test
    fun `malformed or unknown grant result during configuration detach is denied`() =
        gatewayTest { fixture ->
            for (grantResults in listOf(intArrayOf(), intArrayOf(GRANTED, GRANTED), intArrayOf(42))) {
                fixture.gateway.attach(fixture.firstActivity)
                val result = request(fixture)
                fixture.gateway.detachForConfigChange()
                assertTrue(
                    fixture.gateway.onPermissionResult(
                        fixture.dispatched.last().requestCode, arrayOf(CAMERA), grantResults,
                    ),
                )
                assertFalse(result.await())
            }
        }

    @Test
    fun `live permission state overrides a stale granted callback`() = gatewayTest { fixture ->
        val result = request(fixture)
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = true))
        assertFalse(result.await())
    }

    @Test
    fun `live permission state overrides a stale denied callback`() = gatewayTest { fixture ->
        val result = request(fixture)
        fixture.isGranted = true
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertTrue(result.await())
    }

    @Test
    fun `permission dispatch failure completes request and leaves gateway retryable`() = runBlocking {
        val codes = mutableListOf<Int>()
        val gateway = PermissionGateway(
            permissionChecker = { _, _ -> false },
            permissionRequester = { _, _, code ->
                codes += code
                throw IllegalStateException("Activity cannot request permissions")
            },
        )
        gateway.attach(mock(Activity::class.java))
        assertFalse(gateway.requestCameraPermission())
        assertFalse(gateway.requestCameraPermission())
        assertEquals(2, codes.size)
        assertEquals(listOf(0, 0), codes)
        gateway.detachFinal()
    }

    @Test
    fun `request enqueued during a failing dispatch is not stranded`() {
        for (failure in listOf(IllegalStateException("Dispatch failed"), CancellationException("Dispatch cancelled"))) {
            gatewayTest { fixture ->
                var queued: Deferred<Boolean>? = null
                fixture.onDispatch = { dispatched ->
                    if (dispatched.permission == CAMERA) {
                        queued = request(fixture, AUDIO)
                        throw failure
                    }
                }

                val first = request(fixture)
                if (failure is CancellationException) {
                    first.join()
                    assertTrue(first.isCancelled)
                } else {
                    assertFalse(first.await())
                }
                assertEquals(listOf(CAMERA, AUDIO), fixture.dispatched.map { it.permission })
                val next = requireNotNull(queued)
                assertFalse(next.isCompleted)
                assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
                assertFalse(next.await())
            }
        }
    }

    @Test
    fun `failed dispatch cleanup cannot remove a reentrant replacement with the same permission`() {
        for (failure in listOf(IllegalStateException("Dispatch failed"), CancellationException("Dispatch cancelled"))) {
            gatewayTest { fixture ->
                var replacement: Deferred<Boolean>? = null
                fixture.onDispatch = { dispatched ->
                    if (fixture.dispatched.size == 1) {
                        assertTrue(fixture.complete(dispatched, isGranted = false))
                        replacement = request(fixture)
                        throw failure
                    }
                }

                assertFalse(request(fixture).await())
                assertEquals(2, fixture.dispatched.size)
                val next = requireNotNull(replacement)
                assertFalse(next.isCompleted)
                assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = false))
                assertFalse(next.await())
            }
        }
    }

    @Test
    fun `dispatch cancellation propagates and does not strand another request`() = runBlocking {
        val cancellation = CancellationException("Dispatch cancelled")
        var calls = 0
        val gateway = PermissionGateway(
            permissionChecker = { _, _ -> false },
            permissionRequester = { _, _, _ ->
                calls += 1
                throw cancellation
            },
        )
        gateway.attach(mock(Activity::class.java))
        repeat(2) {
            var caught: CancellationException? = null
            try {
                gateway.requestCameraPermission()
            } catch (error: CancellationException) {
                caught = error
            }
            assertEquals(cancellation.message, caught?.message)
            assertTrue(caught === cancellation || caught?.cause === cancellation)
        }
        assertEquals(2, calls)
        gateway.detachFinal()
    }

    @Test
    fun `synchronous permission callback completes the published request`() = runBlocking {
        lateinit var gateway: PermissionGateway
        var granted = false
        gateway = PermissionGateway(
            permissionChecker = { _, _ -> granted },
            permissionRequester = { _, _, code ->
                granted = true
                assertTrue(gateway.onPermissionResult(code, arrayOf(CAMERA), intArrayOf(GRANTED)))
            },
        )
        gateway.attach(mock(Activity::class.java))
        assertTrue(gateway.requestCameraPermission())
        gateway.detachFinal()
    }

    @Test
    fun `resumed caller can retry before previous callback returns`() = gatewayTest { fixture ->
        val results = mutableListOf<Boolean>()
        val caller = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            results += fixture.gateway.requestCameraPermission()
            results += fixture.gateway.requestCameraPermission()
        }
        assertTrue(fixture.complete(fixture.dispatched.single(), isGranted = false))
        assertEquals(listOf(false), results)
        assertEquals(2, fixture.dispatched.size)
        fixture.isGranted = true
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = true))
        caller.await()
        assertEquals(listOf(false, true), results)
    }

    private class Fixture {
        val firstActivity = mock(Activity::class.java)
        val secondActivity = mock(Activity::class.java)
        var isGranted = false
        val grantedPermissions = mutableSetOf<String>()
        val checkedActivities = mutableListOf<Activity>()
        val dispatched = mutableListOf<DispatchedRequest>()
        var onDispatch: (DispatchedRequest) -> Unit = {}
        val gateway = PermissionGateway(
            permissionChecker = { activity, permission ->
                checkedActivities += activity
                isGranted || permission in grantedPermissions
            },
            permissionRequester = { activity, permission, requestCode ->
                val request = DispatchedRequest(activity, requestCode, permission)
                dispatched += request
                onDispatch(request)
            },
        ).also { it.attach(firstActivity) }

        fun complete(request: DispatchedRequest, isGranted: Boolean): Boolean =
            gateway.onPermissionResult(
                request.requestCode,
                arrayOf(request.permission),
                intArrayOf(if (isGranted) GRANTED else PackageManager.PERMISSION_DENIED),
            )
    }

    private data class DispatchedRequest(
        val activity: Activity,
        val requestCode: Int,
        val permission: String,
    )

    private companion object {
        const val CAMERA = Manifest.permission.CAMERA
        const val AUDIO = Manifest.permission.RECORD_AUDIO
        const val LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION
        const val GRANTED = PackageManager.PERMISSION_GRANTED

        fun gatewayTest(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
            val fixture = Fixture()
            try {
                block(fixture)
            } finally {
                fixture.gateway.detachFinal()
            }
        }

        fun CoroutineScope.request(fixture: Fixture, permission: String = CAMERA) =
            async(start = CoroutineStart.UNDISPATCHED) {
                when (permission) {
                    CAMERA -> fixture.gateway.requestCameraPermission()
                    AUDIO -> fixture.gateway.requestPermission(AUDIO, requestCode = 1)
                    LOCATION -> fixture.gateway.requestPermission(LOCATION, requestCode = 2)
                    else -> error("Unexpected test permission: $permission")
                }
            }
    }
}
// endregion

// region PermissionGatewayAndroidTest
/** Exercises the real AndroidX checker/requester, which the state-machine tests replace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class PermissionGatewayAndroidTest {
    @Test
    fun `default requester submits the selected non camera permission`() =
        androidTest { activity, gateway ->
            val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
            shadowOf(activity).denyPermissions(*permissions)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                gateway.requestPermission(Manifest.permission.RECORD_AUDIO, requestCode = 1)
            }
            val request = requireNotNull(shadowOf(activity).lastRequestedPermission)
            assertArrayEquals(permissions, request.requestedPermissions)
            assertEquals(1, request.requestCode)

            shadowOf(activity).grantPermissions(*permissions)
            assertTrue(gateway.onPermissionResult(
                request.requestCode, request.requestedPermissions,
                IntArray(permissions.size) { PackageManager.PERMISSION_GRANTED },
            ))
            assertTrue(result.await())
        }

    @Test
    fun `default checker rejects a stale non camera grant`() =
        androidTest { activity, gateway ->
            val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
            shadowOf(activity).denyPermissions(*permissions)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                gateway.requestPermission(Manifest.permission.RECORD_AUDIO, requestCode = 1)
            }
            val request = requireNotNull(shadowOf(activity).lastRequestedPermission)

            assertTrue(gateway.onPermissionResult(
                request.requestCode, request.requestedPermissions,
                IntArray(permissions.size) { PackageManager.PERMISSION_GRANTED },
            ))
            assertFalse(result.await())
        }

    @Test
    fun `default checker accepts an already granted non camera permission`() =
        androidTest { activity, gateway ->
            shadowOf(activity).denyPermissions(Manifest.permission.CAMERA)
            shadowOf(activity).grantPermissions(Manifest.permission.RECORD_AUDIO)
            assertTrue(gateway.requestPermission(Manifest.permission.RECORD_AUDIO, requestCode = 1))
            assertNull(shadowOf(activity).lastRequestedPermission)
        }

    @Test
    fun `default checker reads a granted CAMERA permission without dispatch`() =
        androidTest { activity, gateway ->
            shadowOf(activity).grantPermissions(Manifest.permission.CAMERA)

            assertTrue(gateway.requestCameraPermission())
            assertNull(shadowOf(activity).lastRequestedPermission)
        }

    @Test
    fun `default requester asks a plain Activity for CAMERA only and observes the grant`() =
        androidTest { activity, gateway ->
            shadowOf(activity).denyPermissions(Manifest.permission.CAMERA)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                gateway.requestCameraPermission()
            }
            val request = requireNotNull(shadowOf(activity).lastRequestedPermission)

            assertArrayEquals(arrayOf(Manifest.permission.CAMERA), request.requestedPermissions)
            assertEquals(0, request.requestCode)
            shadowOf(activity).grantPermissions(Manifest.permission.CAMERA)
            assertTrue(
                gateway.onPermissionResult(
                    request.requestCode, request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_GRANTED),
                ),
            )
            assertTrue(result.await())
        }

    @Test
    fun `default checker rejects a granted callback while Android denies CAMERA`() =
        androidTest { activity, gateway ->
            shadowOf(activity).denyPermissions(Manifest.permission.CAMERA)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                gateway.requestCameraPermission()
            }
            val request = requireNotNull(shadowOf(activity).lastRequestedPermission)

            assertTrue(
                gateway.onPermissionResult(
                    request.requestCode, request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_GRANTED),
                ),
            )
            assertFalse(result.await())
        }

    @Test
    fun `default checker detects a grant on a replacement Activity`() =
        androidTest { activity, gateway ->
            shadowOf(activity).denyPermissions(Manifest.permission.CAMERA)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                gateway.requestCameraPermission()
            }
            gateway.detachForConfigChange()
            val replacement = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                shadowOf(replacement.get()).grantPermissions(Manifest.permission.CAMERA)
                gateway.attach(replacement.get())

                assertTrue(result.await())
                assertNull(shadowOf(replacement.get()).lastRequestedPermission)
            } finally {
                gateway.detachFinal()
                replacement.pause().stop().destroy()
            }
        }

    private fun androidTest(block: suspend CoroutineScope.(Activity, PermissionGateway) -> Unit) =
        runBlocking {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val gateway = PermissionGateway()
            try {
                gateway.attach(controller.get())
                block(controller.get(), gateway)
            } finally {
                gateway.detachFinal()
                controller.pause().stop().destroy()
            }
        }
}
// endregion
