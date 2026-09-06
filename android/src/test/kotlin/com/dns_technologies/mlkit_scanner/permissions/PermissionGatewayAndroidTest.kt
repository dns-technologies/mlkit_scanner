package com.dns_technologies.mlkit_scanner.permissions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
