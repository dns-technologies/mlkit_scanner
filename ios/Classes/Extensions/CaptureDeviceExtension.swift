//
//  DeviceMapExtension.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 14.03.2023.
//

import Foundation
import AVFoundation

extension AVCaptureDevice {
    /// Whether the camera supports point focus, torch and recognized type and position codes.
    var isSupported: Bool {
        isFocusPointOfInterestSupported
        && hasTorch
        && position.code != AVCaptureDevice.Position.unsupportedCode
        && deviceType.code != AVCaptureDevice.DeviceType.unsupportedCode
    }

    /// Creates CameraData from camera.
    func toCameraData() -> CameraData {
        CameraData(type: deviceType, position: position)
    }
}
