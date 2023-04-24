//
//  ScannerParameters.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 13.04.2023.
//

import AVFoundation
import Foundation

/// Initial scanner settings.
///
/// Will be applied to the scanner during initialization.
struct ScannerParameters {
    /// Camera initial zoom.
    let zoom: Double?
    /// Scanner initial scanning area.
    let cropRect: CropRect?
    /// Initial camera.
    let camera: CameraData?
    
    init(arguments: Dictionary<String, Any?>) {
        let zoom = arguments["initialZoom"] as! Double?
        let cropRect = arguments["initialCropRect"] as! Dictionary<String, CGFloat>?
        let camera = arguments["initialCamera"] as! Dictionary<String, Any?>?
        
        self.zoom = zoom
        self.cropRect = cropRect != nil ? CropRect(arguments: cropRect!): nil
        self.camera = camera != nil ? CameraData(arguments: camera!) : nil
    }
}
