package com.dns_technologies.mlkit_scanner.commands

import android.Manifest
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerViewArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
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
        val viewId = ScannerViewArguments.requireViewId(call)
        val scannerSession = scannerSessionProvider()
        if (scannerSession == null) {
            reportError(result, PluginError.CameraIsNotInitialized)
            return
        }

        if (!permissionGateway.requestPermissions(arrayOf(Manifest.permission.CAMERA))) {
            reportError(result, PluginError.AuthorizationCameraError)
            return
        }

        val arguments = call.arguments as? Map<*, *> ?: throw PluginError.InvalidArguments
        val initialZoom = when (val value = arguments[PluginConstants.initialZoomArgument]) {
            null -> null
            is Number -> value.toDouble()
            else -> throw PluginError.InvalidArguments
        }
        val initialCropRect = when (val value = arguments[PluginConstants.initialCropRectArgument]) {
            null -> null
            is Map<*, *> -> RecognizeVisorCropRect.fromMap(value)
            else -> throw PluginError.InvalidArguments
        }
        scannerSession.startCamera(
            viewId,
            initialZoom,
            initialCropRect,
        )
        success(result)
    }
}
