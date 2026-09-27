//
//  CameraData.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 17.04.2023.
//

import AVFoundation
import Foundation

/// Camera selection by device type and position.
struct CameraData {
    /// Physical lens or combined camera device to select.
    let type: AVCaptureDevice.DeviceType

    /// Direction the camera faces relative to the user.
    let position: AVCaptureDevice.Position

    init(arguments: [String: Any]) throws {
        let typeNumber = try PlatformChannelScalar.number(from: arguments["type"])
        let positionNumber = try PlatformChannelScalar.number(from: arguments["position"])
        guard
            typeNumber.doubleValue == Double(typeNumber.intValue),
            positionNumber.doubleValue == Double(positionNumber.intValue),
            let type = AVCaptureDevice.DeviceType.fromCode(typeNumber.intValue),
            let position = AVCaptureDevice.Position.fromCode(positionNumber.intValue)
        else {
            throw MlKitPluginError.invalidArguments
        }
        self.type = type
        self.position = position
    }

    init(type: AVCaptureDevice.DeviceType, position: AVCaptureDevice.Position) {
        self.type = type
        self.position = position
    }

    /// Creates a JSON-compatible platform-channel representation.
    func toJson() -> [String: Any] {
        [
            "position": position.code,
            "type": type.code,
        ]
    }
}
