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

    init(arguments: Dictionary<String, Any?>) {
        self.type = AVCaptureDevice.DeviceType.fromCode(arguments["type"] as! Int)!
        self.position = AVCaptureDevice.Position.fromCode(arguments["position"] as! Int)!
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
