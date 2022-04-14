package com.dns_technologies.mlkit_scanner

/** Method channel function constants */
class PluginConstants {
    companion object {
        const val channelName = "mlkit_channel"
        const val cameraPlatformViewName = "mlkit/camera_preview"
        const val initCameraMethod = "initCameraPreview"
        const val resumeCameraMethod = "resumeCameraMethod"
        const val updateConstraintsMethod = "updateConstraints"
        const val pauseCameraMethod = "pauseCameraMethod"
        const val disposeCameraMethod = "dispose"
        const val toggleFlashMethod = "toggleFlash"
        const val startScanMethod = "startScan"
        const val cancelScanMethod = "cancelScan"
        const val setScanDelayMethod = "setScanDelay"
        const val scanResultMethod = "onScanResult"
        const val setZoomMethod = "setZoom"
        const val setCropAreaMethod = "setCropAreaMethod"
    }
}