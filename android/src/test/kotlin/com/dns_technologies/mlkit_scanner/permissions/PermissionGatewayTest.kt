package com.dns_technologies.mlkit_scanner.permissions

import android.content.pm.PackageManager
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

internal class PermissionGatewayTest {
    @Test
    fun `configuration detach preserves active request until callback after reattach`() =
        runBlocking {
            val fixture = Fixture()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
            }
            val dispatched = fixture.dispatched.single()

            fixture.gateway.detachForConfigChange()
            assertFalse(result.isCompleted)
            fixture.gateway.attach(fixture.secondBinding)
            assertFalse(result.isCompleted)

            fixture.grantedPermissions += CAMERA_PERMISSION
            assertTrue(fixture.complete(dispatched, isGranted = true))
            assertTrue(result.await())
            assertEquals(1, fixture.dispatched.size)
        }

    @Test
    fun `matching callback can complete active request during configuration detach`() =
        runBlocking {
            val fixture = Fixture()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
            }
            val dispatched = fixture.dispatched.single()

            fixture.gateway.detachForConfigChange()

            assertTrue(fixture.complete(dispatched, isGranted = true))
            assertTrue(result.await())
        }

    @Test
    fun `reattach completes active request when permission is already granted`() = runBlocking {
        val fixture = Fixture()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
        }

        fixture.gateway.detachForConfigChange()
        fixture.grantedPermissions += CAMERA_PERMISSION
        fixture.gateway.attach(fixture.secondBinding)

        assertTrue(result.await())
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun `final detach completes requests as denied and clears active attempt`() = runBlocking {
        val fixture = Fixture()
        val activeResult = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
        }
        val queuedResult = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(OTHER_PERMISSION))
        }
        val dispatched = fixture.dispatched.single()

        fixture.gateway.detachFinal()

        assertFalse(activeResult.await())
        assertFalse(queuedResult.await())
        assertFalse(fixture.complete(dispatched, isGranted = true))
    }

    @Test
    fun `unrelated permission callbacks are not consumed`() = runBlocking {
        val fixture = Fixture()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
        }
        val dispatched = fixture.dispatched.single()

        assertFalse(
            fixture.gateway.onPermissionResult(
                dispatched.requestCode + 1,
                dispatched.permissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            ),
        )
        assertFalse(
            fixture.gateway.onPermissionResult(
                dispatched.requestCode,
                arrayOf(OTHER_PERMISSION),
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            ),
        )
        assertFalse(result.isCompleted)

        fixture.grantedPermissions += CAMERA_PERMISSION
        assertTrue(fixture.complete(dispatched, isGranted = true))
        assertTrue(result.await())
    }

    @Test
    fun `interrupted permission dialog completes active request as denied`() = runBlocking {
        val fixture = Fixture()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
        }
        val dispatched = fixture.dispatched.single()

        assertTrue(
            fixture.gateway.onPermissionResult(
                dispatched.requestCode,
                emptyArray(),
                intArrayOf(),
            ),
        )
        assertFalse(result.await())
    }

    @Test
    fun `equivalent requests aggregate while different permissions remain queued`() = runBlocking {
        val fixture = Fixture()
        val cancelledCameraResult = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
        }
        val cameraResult = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(CAMERA_PERMISSION))
        }
        val otherResult = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.gateway.requestPermissions(arrayOf(OTHER_PERMISSION))
        }
        assertEquals(1, fixture.dispatched.size)

        cancelledCameraResult.cancelAndJoin()
        fixture.grantedPermissions += CAMERA_PERMISSION
        assertTrue(fixture.complete(fixture.dispatched.first(), isGranted = true))

        assertTrue(cameraResult.await())
        assertTrue(cancelledCameraResult.isCancelled)
        assertEquals(2, fixture.dispatched.size)

        fixture.grantedPermissions += OTHER_PERMISSION
        assertTrue(fixture.complete(fixture.dispatched.last(), isGranted = true))
        assertTrue(otherResult.await())
    }

    @Test
    fun `permission dispatch failure completes request and leaves gateway retryable`() = runBlocking {
        val binding = mock(ActivityPluginBinding::class.java)
        var dispatchCalls = 0
        val gateway = PermissionGateway(
            permissionChecker = { _, _ -> false },
            permissionRequester = { _, _, _ ->
                dispatchCalls += 1
                throw IllegalStateException("Activity cannot request permissions")
            },
        )
        gateway.attach(binding)

        assertFalse(gateway.requestPermissions(arrayOf(CAMERA_PERMISSION)))
        assertFalse(gateway.requestPermissions(arrayOf(CAMERA_PERMISSION)))

        assertEquals(2, dispatchCalls)
    }

    private class Fixture {
        val firstBinding = mock(ActivityPluginBinding::class.java)
        val secondBinding = mock(ActivityPluginBinding::class.java)
        val grantedPermissions = mutableSetOf<String>()
        val dispatched = mutableListOf<DispatchedPermissionRequest>()
        val gateway = PermissionGateway(
            permissionChecker = { _, permission -> permission in grantedPermissions },
            permissionRequester = { binding, permissions, requestCode ->
                dispatched += DispatchedPermissionRequest(binding, permissions, requestCode)
            },
        )

        init {
            gateway.attach(firstBinding)
        }

        fun complete(
            request: DispatchedPermissionRequest,
            isGranted: Boolean,
        ): Boolean = gateway.onPermissionResult(
            request.requestCode,
            request.permissions,
            IntArray(request.permissions.size) {
                if (isGranted) {
                    PackageManager.PERMISSION_GRANTED
                } else {
                    PackageManager.PERMISSION_DENIED
                }
            },
        )
    }

    private data class DispatchedPermissionRequest(
        val binding: ActivityPluginBinding,
        val permissions: Array<String>,
        val requestCode: Int,
    )

    private companion object {
        const val CAMERA_PERMISSION = "android.permission.CAMERA"
        const val OTHER_PERMISSION = "android.permission.RECORD_AUDIO"
    }
}
