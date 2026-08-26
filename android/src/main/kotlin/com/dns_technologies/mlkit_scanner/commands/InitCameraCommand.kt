package com.dns_technologies.mlkit_scanner.commands

import android.Manifest
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
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
        val arguments = ScannerMethodArguments.cameraInitialization(call.arguments)
        val scannerSession = requireScannerSession()

        if (!permissionGateway.requestPermissions(arrayOf(Manifest.permission.CAMERA))) {
            reportError(result, PluginError.AuthorizationCameraError)
            return
        }

        try {
            scannerSession.startCamera(
                arguments.viewId,
                arguments.initialZoom,
                arguments.initialCropRect,
            )
        } catch (error: PluginError) {
            throw error
        } catch (_: Exception) {
            throw PluginError.InitCameraError
        }
        success(result)
    }
}
