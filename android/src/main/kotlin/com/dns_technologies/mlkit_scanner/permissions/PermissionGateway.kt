package com.dns_technologies.mlkit_scanner.permissions

import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import kotlinx.coroutines.CompletableDeferred

/** Wraps Android runtime permission checks and requests for the plugin. */
internal class PermissionGateway {
    private var binding: ActivityPluginBinding? = null
    private val pendingPermissionRequests = linkedMapOf<PermissionRequestKey, PermissionRequest>()
    private var activePermissionRequest: PermissionRequest? = null

    /** Stores the current activity binding used for permission operations. */
    fun attach(binding: ActivityPluginBinding) {
        this.binding = binding
    }

    /** Clears the current activity binding. */
    fun detach() {
        completePendingPermissionRequests(isGranted = false)
        binding = null
    }

    /** Returns true when all requested Android permissions are granted. */
    fun allPermissionsGranted(permissions: Array<String>): Boolean {
        val activeBinding = binding ?: return false
        return permissions.all {
            ContextCompat.checkSelfPermission(
                activeBinding.activity.baseContext,
                it,
            ) == PackageManager.PERMISSION_GRANTED
        }
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
        val permissionResult = permissionRequest.await()

        processNextPermissionRequest()
        return permissionResult.await()
    }

    /** Handles Android permission request results for plugin permission requests. */
    @Suppress("UNUSED_PARAMETER")
    fun onPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE_PERMISSIONS) return false
        completeActivePermissionRequest()
        processNextPermissionRequest()
        return true
    }

    /** Starts the next queued permission request if no Android permission dialog is active. */
    private fun processNextPermissionRequest() {
        if (activePermissionRequest != null) return

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

            val activeBinding = binding
            if (activeBinding == null) {
                pendingPermissionRequests.remove(PermissionRequestKey(request.permissions))
                request.complete(isGranted = false)
                continue
            }

            activePermissionRequest = request
            ActivityCompat.requestPermissions(
                activeBinding.activity,
                missingPermissions,
                REQUEST_CODE_PERMISSIONS,
            )
            return
        }
    }

    /** Completes the currently active request using the final state of its requested permissions. */
    private fun completeActivePermissionRequest() {
        val request = activePermissionRequest ?: return
        activePermissionRequest = null
        pendingPermissionRequests.remove(PermissionRequestKey(request.permissions))
        request.complete(allPermissionsGranted(request.permissions.toTypedArray()))
    }

    /** Completes and clears every request that can no longer be resolved by Android. */
    private fun completePendingPermissionRequests(isGranted: Boolean) {
        activePermissionRequest?.complete(isGranted)
        activePermissionRequest = null

        pendingPermissionRequests.values.forEach { request ->
            request.complete(isGranted)
        }
        pendingPermissionRequests.clear()
    }

    /** Returns true when a single permission is already granted. */
    private fun isPermissionGranted(permission: String): Boolean {
        val activeBinding = binding ?: return false
        return ContextCompat.checkSelfPermission(
            activeBinding.activity.baseContext,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Normalizes permission arrays so equivalent sets share the same pending request. */
    private fun Array<String>.normalized(): List<String> = distinct().sorted()

    /** Identifies permission requests that can be safely aggregated. */
    private data class PermissionRequestKey(
        val permissions: List<String>,
    )

    /** Stores actions waiting for one requested permission set. */
    private class PermissionRequest(
        val permissions: List<String>,
    ) {
        private val deferredResults = mutableSetOf<CompletableDeferred<Boolean>>()

        /** Adds a caller callback to this permission request. */
        fun await(): CompletableDeferred<Boolean> {
            val deferred = CompletableDeferred<Boolean>()
            deferredResults += deferred
            return deferred
        }

        /** Removes a caller callback from this permission request. */
        private fun cleanupCompleted() {
            deferredResults.removeIf { it.isCompleted || it.isCancelled }
        }

        /** Returns true when no callers are waiting for this request. */
        fun isEmpty(): Boolean {
            cleanupCompleted()
            return deferredResults.isEmpty()
        }

        /** Notifies every caller about the final state of the requested permission set. */
        fun complete(isGranted: Boolean) {
            val awaiting = deferredResults.toList()
            deferredResults.clear()
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
