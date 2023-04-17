//
//  InitialScannerParameters.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 13.04.2023.
//

import AVFoundation
import Foundation

/// Initial scanner settings.
///
/// Will be applied to the scanner during initialization.
struct InitialScannerParameters {
    /// Camera initial zoom.
    let initialZoom: Double?
    /// Scanner initial scanning area.
    let initialCropRect: CropRect?
    /// Initial camera.
    let initialCamera: CameraData?
    
    init?(arguments: Dictionary<String, Any?>?) {
        if (arguments == nil) {
            return nil
        }
        
        guard let initialZoom = arguments!["initialZoom"] as? Double?,
              let initialCropRect = arguments!["initialCropRect"] as? Dictionary<String, CGFloat>?,
              let initialCamera = arguments!["initialCamera"] as? Dictionary<String, Any?>? else {
            return nil
        }
        self.initialZoom = initialZoom
        self.initialCropRect = initialCropRect != nil ? CropRect(arguments: initialCropRect!): nil
        self.initialCamera = initialCamera != nil ? CameraData(arguments: initialCamera!) : nil
    }
}
