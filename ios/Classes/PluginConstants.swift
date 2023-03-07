//
//  PluginConstants.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 02.03.2021.
//

import Foundation

/// Constants of the Plugin.
class PluginConstants {
    /// Method name for camera initialization.
    static let initCameraMethod = "initCameraPreview"
    /// Method name for cleaning camera resources.
    static let disposeMethod = "dispose"
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
    /// Method name for updating native view constraints.
    static let changeConstraintsMethod = "updateConstraints"
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
        
    static let getIosAvailableCamerasMethod = "getIosAvailableCameras"
    
    static let setIosCameraMethod = "setIosCamera"
}
