//
//  RecognitionHandler.swift
//  GoogleDataTransport
//
//  Created by ООО "ДНС Технологии" on 10.03.2021.
//

import Foundation
import AVFoundation
import MLKitBarcodeScanning

/// Processes camera frames without knowledge of Flutter view ownership.
protocol RecognitionHandler: AnyObject {
    /// Recognition mode implemented by this handler.
    var type: RecognitionType { get }
    
    /// Updates the cooldown applied after successful recognition.
    func setDelay(delay: Int)
    
    /// Processes one camera frame using preview scale and video orientation.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation)
    
    /// Updates normalized barcode recognition geometry.
    func updateCropRect(cropRect: CropRect)
}
