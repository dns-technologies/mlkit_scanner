package com.dns_technologies.mlkit_scanner.commands

import android.Manifest
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Initializes scanner camera and permission flow. */
internal class InitCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
    private val permissionGateway: PermissionGateway,
    private val commandScope: CoroutineScope,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        commandScope.launch {
            try {
                if (!permissionGateway.requestPermissions(arrayOf(Manifest.permission.CAMERA))) {
                    reportError(result, PluginError.AuthorizationCameraError)
                    return@launch
                }

                val scannerSession = scannerSessionProvider()
                if (scannerSession == null) {
                    reportError(result, PluginError.CameraIsNotInitialized)
                    return@launch
                }

                if (scannerSession.isActive()) {
                    success(result)
                    return@launch
                }

                @Suppress("UNCHECKED_CAST")
                scannerSession.startCamera(
                    InitialScannerParameters(call.arguments as Map<String, Any?>),
                )
                success(result)
            } catch (error: Exception) {
                reportError(result, error)
            }
        }
    }
}
