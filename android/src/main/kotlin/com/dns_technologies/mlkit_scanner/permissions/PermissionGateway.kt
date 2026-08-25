package com.dns_technologies.mlkit_scanner.permissions

import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.CopyOnWriteArraySet

/** Wraps Android runtime permission checks and requests for the plugin. */
internal class PermissionGateway(
    private val permissionChecker: (ActivityPluginBinding, String) -> Boolean =
        { binding, permission ->
            ContextCompat.checkSelfPermission(
                binding.activity.baseContext,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        },
    private val permissionRequester: (ActivityPluginBinding, Array<String>, Int) -> Unit =
        { binding, permissions, requestCode ->
            ActivityCompat.requestPermissions(binding.activity, permissions, requestCode)
        },
) {
    private var binding: ActivityPluginBinding? = null
    private val pendingPermissionRequests = linkedMapOf<PermissionRequestKey, PermissionRequest>()
    private var activePermissionAttempt: ActivePermissionAttempt? = null

    /** Stores the current activity binding and resumes queued permission work. */
    fun attach(binding: ActivityPluginBinding) {
        this.binding = binding
        val activeAttempt = activePermissionAttempt
        if (
            activeAttempt != null &&
            allPermissionsGranted(activeAttempt.request.permissions.toTypedArray())
        ) {
            completeActivePermissionAttempt(isGranted = true)
        }
        processNextPermissionRequest()
    }

    /** Drops only the old activity binding while preserving requests across configuration change. */
    fun detachForConfigChange() {
        binding = null
    }

    /** Clears the final activity binding and completes requests that can no longer finish. */
    fun detachFinal() {
        binding = null
        val requests = pendingPermissionRequests.values.toMutableSet()
        activePermissionAttempt?.request?.let(requests::add)
        activePermissionAttempt = null
        pendingPermissionRequests.clear()
        requests.forEach { request -> request.complete(isGranted = false) }
    }

    /** Returns true when all requested Android permissions are granted. */
    fun allPermissionsGranted(permissions: Array<String>): Boolean {
        val activeBinding = binding ?: return false
        return permissions.all { permission -> permissionChecker(activeBinding, permission) }
    }

    /** Requests missing Android permissions and suspends until a single result is known. */
    suspend fun requestPermissions(
        permissions: Array<String>,
    ): Boolean {
        val requestedPermissions = permissions.normalized()
        if (requestedPermissions.isEmpty() || allPermissionsGranted(requestedPermissions.toTypedArray())) {
            return true
        }

        val activeBinding = binding
        if (activeBinding == null) {
            return false
        }

        val requestKey = PermissionRequestKey(requestedPermissions)
        val permissionRequest = pendingPermissionRequests.getOrPut(requestKey) {
            PermissionRequest(requestedPermissions)
        }
        val permissionResult = permissionRequest.registerAwaiter(currentCoroutineContext()[Job])

        processNextPermissionRequest()
        return permissionResult.await()
    }

    /** Handles Android permission request results for plugin permission requests. */
    fun onPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean {
        val activeAttempt = activePermissionAttempt ?: return false
        if (requestCode != activeAttempt.requestCode) return false
        if (permissions.normalized() != activeAttempt.dispatchedPermissions) return false

        val isGranted = binding?.let { activeBinding ->
            activeAttempt.request.permissions.all { permission ->
                permissionChecker(activeBinding, permission)
            }
        } ?: (
            grantResults.size == permissions.size &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        )
        completeActivePermissionAttempt(isGranted)
        processNextPermissionRequest()
        return true
    }

    /** Starts the next queued permission request if no Android permission dialog is active. */
    private fun processNextPermissionRequest() {
        if (activePermissionAttempt != null) return
        val activeBinding = binding ?: return

        while (pendingPermissionRequests.isNotEmpty()) {
            val request = pendingPermissionRequests.values.first()
            if (request.isEmpty()) {
                pendingPermissionRequests.remove(PermissionRequestKey(request.permissions))
                continue
            }

            val missingPermissions = request.permissions
                .filterNot { permission -> isPermissionGranted(permission) }
                .toTypedArray()
            if (missingPermissions.isEmpty()) {
                pendingPermissionRequests.remove(PermissionRequestKey(request.permissions))
                request.complete(isGranted = true)
                continue
            }

            activePermissionAttempt = ActivePermissionAttempt(
                requestCode = REQUEST_CODE_PERMISSIONS,
                request = request,
                dispatchedPermissions = missingPermissions.normalized(),
            )
            permissionRequester(activeBinding, missingPermissions, REQUEST_CODE_PERMISSIONS)
            return
        }
    }

    /** Completes the currently active request using the final state of its requested permissions. */
    private fun completeActivePermissionAttempt(isGranted: Boolean) {
        val request = activePermissionAttempt?.request ?: return
        activePermissionAttempt = null
        pendingPermissionRequests.remove(PermissionRequestKey(request.permissions))
        request.complete(isGranted)
    }

    /** Returns true when a single permission is already granted. */
    private fun isPermissionGranted(permission: String): Boolean {
        val activeBinding = binding ?: return false
        return permissionChecker(activeBinding, permission)
    }

    /** Normalizes permission arrays so equivalent sets share the same pending request. */
    private fun Array<String>.normalized(): List<String> = distinct().sorted()

    /** Identifies permission requests that can be safely aggregated. */
    private data class PermissionRequestKey(
        val permissions: List<String>,
    )

    /** Describes the exact Android permission dialog currently awaiting a callback. */
    private data class ActivePermissionAttempt(
        val requestCode: Int,
        val request: PermissionRequest,
        val dispatchedPermissions: List<String>,
    )

    /** Stores actions waiting for one requested permission set. */
    private class PermissionRequest(
        val permissions: List<String>,
    ) {
        private val deferredResults = CopyOnWriteArraySet<CompletableDeferred<Boolean>>()

        /** Registers a result holder for a caller waiting on this permission request. */
        fun registerAwaiter(parentJob: Job?): CompletableDeferred<Boolean> {
            val deferred = CompletableDeferred<Boolean>(parentJob)
            deferredResults += deferred
            deferred.invokeOnCompletion { deferredResults -= deferred }
            return deferred
        }

        /** Returns true when no callers are waiting for this request. */
        fun isEmpty(): Boolean = deferredResults.isEmpty()

        /** Notifies every caller about the final state of the requested permission set. */
        fun complete(isGranted: Boolean) {
            val awaiting = deferredResults.toList()
            awaiting.forEach { deferred ->
                if (!deferred.isCompleted && !deferred.isCancelled) {
                    deferred.complete(isGranted)
                }
            }
        }
    }

    private companion object {
        /** Request code used for plugin runtime permission requests. */
        const val REQUEST_CODE_PERMISSIONS = 6666
    }
}
