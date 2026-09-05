package com.dns_technologies.mlkit_scanner.commands

import android.Manifest
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.session.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** Transfers camera ownership to one view and starts/restores the shared Android pipeline. */
internal class CaptureCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
    private val permissionGateway: PermissionGateway,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    /** Runs the single permission-aware session capture transaction. */
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val viewId = call.arguments.requireMap().requireInt(PluginConstants.viewIdArgument)
        if (viewId < 0) throw PluginError.InvalidArguments
        val scannerSession = scannerSession() ?: run {
            // The platform view may have been disposed while this method-channel message was
            // already in flight. Treat it as superseded work instead of failing a new screen.
            success(result)
            return
        }

        try {
            scannerSession.captureCamera(viewId) {
                permissionGateway.requestPermissions(arrayOf(Manifest.permission.CAMERA))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PluginError) {
            throw error
        } catch (_: Exception) {
            throw PluginError.InitCameraError
        }
        success(result)
    }
}
