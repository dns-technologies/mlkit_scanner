package com.dns_technologies.mlkit_scanner.commands

import android.Manifest
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Initializes scanner camera and permission flow. */
internal class InitCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
    private val permissionGateway: PermissionGateway,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        if (!permissionGateway.requestPermissions(arrayOf(Manifest.permission.CAMERA))) {
            reportError(result, PluginError.AuthorizationCameraError)
            return
        }

        val scannerSession = scannerSessionProvider()
        if (scannerSession == null) {
            reportError(result, PluginError.CameraIsNotInitialized)
            return
        }

        if (scannerSession.isActive()) {
            success(result)
            return
        }

        @Suppress("UNCHECKED_CAST")
        scannerSession.startCamera(
            (call.arguments as? Map<String, Any?>)?.let(::InitialScannerParameters),
        )
        success(result)
    }
}
