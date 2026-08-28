package com.dns_technologies.mlkit_scanner.commands

import android.Manifest
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.BaseScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Transfers camera ownership to one view and starts/restores the shared Android pipeline. */
internal class CaptureCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
    private val permissionGateway: PermissionGateway,
    private val commandScope: CoroutineScope,
) : BaseScannerCommand(scannerSessionProvider) {
    /** Validates the request before running the single session capture transaction. */
    fun execute(call: MethodCall, result: Result) {
        val scannerSession: ScannerSession
        val viewId = try {
            scannerSession = requireScannerSession()
            ScannerMethodArguments.viewId(call.arguments)
        } catch (error: Exception) {
            reportError(result, error)
            return
        }

        commandScope.launch {
            try {
                scannerSession.captureCamera(viewId) {
                    permissionGateway.requestPermissions(arrayOf(Manifest.permission.CAMERA))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: PluginError) {
                reportError(result, error)
                return@launch
            } catch (_: Exception) {
                reportError(result, PluginError.InitCameraError)
                return@launch
            }
            success(result)
        }
    }
}
