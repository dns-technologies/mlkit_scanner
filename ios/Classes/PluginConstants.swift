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
    /// Event key containing a recognized barcode.
    static let barcodeArgument = "barcode"
    /// Event key containing a scalar event value.
    static let valueArgument = "value"
    /// Argument key containing the scan cooldown.
    static let delayArgument = "delay"
    /// Argument key containing a recognition area.
    static let cropRectArgument = "cropRect"
    /// Argument key containing a recognition type.
    static let typeArgument = "type"
    static let setZoomRatioMethod = "setZoomRatio"
    static let toggleFlashMethod = "toggleFlash"
    static let startScanMethod = "startScan"
    static let cancelScanMethod = "cancelScan"
    static let setScanDelayMethod = "setScanDelay"
    static let setCropAreaMethod = "setCropArea"
    /// Camera ownership capture.
    static let captureCameraMethod = "captureCamera"
    /// Camera ownership release.
    static let releaseCameraMethod = "releaseCamera"
    /// Method name for invoking Flutter Side method with success recognitions.
    static let scanResultMethod = "onScanResult"
    /// Method name for inform flutter side when torch change state
    static let changeTorchStateMethod = "changeTorchStateMethod"
    /// Method name for getting available iOS cameras.
    static let getIosAvailableCamerasMethod = "getIosAvailableCameras"
}
