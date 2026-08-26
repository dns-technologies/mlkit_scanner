package com.dns_technologies.mlkit_scanner

/** Contains method channel names used by the Dart and Android plugin sides. */
internal object PluginConstants {
    /** Argument key identifying the platform view that owns a command or event. */
    const val viewIdArgument = "viewId"

    /** Event key containing the recognized barcode payload. */
    const val barcodeArgument = "barcode"

    /** Optional camera-initialization argument containing normalized zoom. */
    const val initialZoomArgument = "initialZoom"

    /** Optional camera-initialization argument containing the recognition area. */
    const val initialCropRectArgument = "initialCropRect"

    /** Method channel name used for scanner commands and events. */
    const val channelName = "mlkit_channel"

    /** Platform view type name registered for the native camera preview. */
    const val cameraPlatformViewName = "mlkit/camera_preview"

    /** Method name used to initialize the native camera preview. */
    const val initCameraMethod = "initCameraPreview"

    /** Method name used to resume the camera preview lifecycle. */
    const val resumeCameraMethod = "resumeCameraMethod"

    /** Method name used to update scanner layout constraints from Dart. */
    const val updateConstraintsMethod = "updateConstraints"

    /** Method name used to pause the camera preview lifecycle. */
    const val pauseCameraMethod = "pauseCameraMethod"

    /** Method name used to release native camera resources. */
    const val disposeCameraMethod = "dispose"

    /** Method name used to toggle the camera torch. */
    const val toggleFlashMethod = "toggleFlash"

    /** Method name used to start barcode analysis. */
    const val startScanMethod = "startScan"

    /** Method name used to stop barcode analysis. */
    const val cancelScanMethod = "cancelScan"

    /** Method name used to update the cooldown between analysis windows. */
    const val setScanDelayMethod = "setScanDelay"

    /** Method name used to deliver recognized barcode values to Dart. */
    const val scanResultMethod = "onScanResult"

    /** Method name used to update camera zoom. */
    const val setZoomMethod = "setZoom"

    /** Method name used to update the recognized visor crop area. */
    const val setCropAreaMethod = "setCropAreaMethod"
}
