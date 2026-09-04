//
//  RecognitionHandler.swift
//  GoogleDataTransport
//
//  Created by ООО "ДНС Технологии" on 10.03.2021.
//

import Foundation
import AVFoundation
import MLKitBarcodeScanning

/// Delegate of the recognition results.
protocol RecognitionResultDelegate: AnyObject {
    /// Call delegate on success recognition.
    func onRecognition(result: Barcode, viewId: Int64)
    
    /// Call delegate on recognition error.
    func onError(error: Error)
}

/// Processes camera frames for one recognition mode and platform view.
protocol RecognitionHandler: AnyObject {
    /// Recognition mode implemented by this handler.
    var type: RecognitionType { get }
    
    /// Delegate of the recognition results.
    var delegate: RecognitionResultDelegate? {get set}
    
    /// Creates a handler with a platform cooldown and optional crop.
    init(delay: Int, cropRect: CropRect?, viewId: Int64)

    /// Updates the cooldown applied after successful recognition.
    func setDelay(delay: Int)
    
    /// Processes one camera frame using preview scale and video orientation.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation)
    
    /// Updates normalized barcode recognition geometry.
    func updateCropRect(cropRect: CropRect)
}
