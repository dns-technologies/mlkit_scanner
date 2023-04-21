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
    func onRecognition(result: Barcode)
    
    /// Call delegate on recognition error.
    func onError(error: Error)
}

/// Protocol with methods of RecognitionHandler
protocol RecognitionHandler: AnyObject {
    /// Return the type of RecognitionHandler
    var type: RecognitionType { get }
    
    /// Delegate of the recognition results.
    var delegate: RecognitionResultDelegate? {get set}
    
    /// Initialization of RecognitionHandler.
    /// `delay` - delay between detections
    /// `cropRect` optional `CropRect`, area of the detection.
    init(delay: Int, cropRect: CropRect?)

    /// Set delay when detection is active.
    /// `delay` - delay between detections
    func setDelay(delay: Int)
    
    /// Method for calling processing of video output. 
    /// `sampleBuffer` - frame from camera `CaptureSession`
    /// `scaleX` - Scale of CameraPreview width relative to width of the screen.
    /// `scaleY`- Scale of CameraPreview height relative to height of the screen.
    /// `orientation` - orientation of `CaptureSession`
    func proccessVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation)
    
    /// Update area of the barcode detection
    /// `cropRect` -  area of the detection.
    func updateCropRect(cropRect: CropRect)
}
