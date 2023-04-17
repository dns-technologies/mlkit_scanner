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

    init?(arguments: Dictionary<String, Any?>?) {
        if (arguments == nil) {
            return nil
        }
        
        guard let type = AVCaptureDevice.DeviceType.fromCode(arguments!["type"] as! Int),
              let position = AVCaptureDevice.Position.fromCode(arguments!["position"] as! Int) else {
            return nil
        }
        self.type = type
        self.position = position
    }
}
