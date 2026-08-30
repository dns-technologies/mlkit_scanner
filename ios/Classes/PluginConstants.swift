//
//  PluginConstants.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 02.03.2021.
//

import Foundation

/// Constants of the Plugin.
class PluginConstants {
    /// Shared method channel name.
    static let channelName = "mlkit_channel"
    /// Native platform view type.
    static let cameraPlatformViewName = "mlkit/camera_preview"
    /// Argument key identifying a Flutter platform view.
    static let viewIdArgument = "viewId"
    /// Platform-view registration width.
    static let widthArgument = "width"
    /// Platform-view registration height.
    static let heightArgument = "height"
    /// Event key containing a recognized barcode.
    static let barcodeArgument = "barcode"
    /// Optional camera-initialization argument containing normalized zoom.
    static let initialZoomArgument = "initialZoom"
    /// Optional camera-initialization argument containing the initial torch state.
    static let initialFlashEnabledArgument = "initialFlashEnabled"
    /// Optional camera-initialization argument containing the recognition area.
    static let initialCropRectArgument = "initialCropRect"
    /// Optional camera-initialization argument containing an iOS camera.
    static let initialCameraArgument = "initialCamera"
    /// Event key containing a scalar event value.
    static let valueArgument = "value"
    /// Argument key containing the scan cooldown.
    static let delayArgument = "delay"
    /// Argument key containing a recognition area.
    static let cropRectArgument = "cropRect"
    /// Argument key containing a recognition type.
    static let typeArgument = "type"
    /// Camera ownership capture.
    static let captureCameraMethod = "captureCamera"
    /// Camera ownership release.
    static let releaseCameraMethod = "releaseCamera"
    /// Method name for toggling flash of the device.
    static let toggleFlashMethod = "toggleFlash"
    /// Method name for starting use recognizers.
    static let startScanMethod = "startScan"
    /// Method name for cancelling recognition.
    static let cancelScanMethod = "cancelScan"
    /// Method name for setting delay between detections.
    static let setScanDelayMethod = "setScanDelay"
    /// Method name for invoking Flutter Side method with success recognitions.
    static let scanResultMethod = "onScanResult"
    /// Method name for pausing camera preview.
    static let pauseCameraMethod = "pauseCameraMethod"
    /// Method name for resuming camera preview.
    static let resumeCameraMethod = "resumeCameraMethod"
    /// Method name for inform flutter side when torch change state
    static let changeTorchStateMethod = "changeTorchStateMethod"
    /// Method name for setting zoom scale of the camera
    static let setZoomMethod = "setZoom"
    /// Method name for setting crop area and adding overlay to the camera preview
    static let setCropAreaMethod = "setCropAreaMethod"
    /// Method name for getting available iOS cameras.
    static let getIosAvailableCamerasMethod = "getIosAvailableCameras"
    /// Method name for setting iOS camera.
    static let setIosCameraMethod = "setIosCamera"
}
