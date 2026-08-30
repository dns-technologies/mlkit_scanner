//
//  CameraData.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 17.04.2023.
//

import AVFoundation
import Foundation

/// Camera Information.
struct CameraData {
    /// Camera type.
    let type: AVCaptureDevice.DeviceType

    /// Camera position.
    let position: AVCaptureDevice.Position

    init(arguments: [String: Any]) throws {
        guard
            !(arguments["type"] is Bool),
            !(arguments["position"] is Bool),
            let typeNumber = arguments["type"] as? NSNumber,
            let positionNumber = arguments["position"] as? NSNumber,
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
    
    /// Creates json for transmission over the platform channel.
    func toJson() -> [String: Any] {
        [
            "position": position.code,
            "type": type.code,
        ]
    }
}
