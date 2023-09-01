//
//  MlkitBarcodeScanner.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 05.03.2021.
//

import Foundation
import AVFoundation
import MLKitBarcodeScanning
import MLKitVision

/// Barcode scanner recognizer, uses MLkit Barcode Scanning API
class MlkitBarcodeScanner: NSObject, RecognitionHandler {
    private let scanner: BarcodeScanner
    private var delay: Int
    private var isDelayed = false
    private var cropRect: CropRect?
    private var isRecognitionInProgress = false
    
    var type: RecognitionType = RecognitionType.barcodeRecognition
    weak var delegate: RecognitionResultDelegate?
    
    /// Can the barcode be recognized.
    ///
    /// Used for optimization so as not to recognize the barcode on every frame.
    var canRecognize: Bool {
        !isDelayed && !isRecognitionInProgress
    }
    
    required init(delay: Int, cropRect: CropRect?) {
        scanner = BarcodeScanner.barcodeScanner()
        self.delay = delay
        self.cropRect = cropRect
        super.init()
        startDelay()
    }
    
    /// Recognizes a barcode on frame [sampleBuffer].
    func proccessVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation) {
        if (!canRecognize) {
            return
        }
        isRecognitionInProgress = true
        
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            isRecognitionInProgress = false
            return
        }
        
        let cimage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let image = UIImage(ciImage: cimage, scaleX: scaleX, scaleY: scaleY, orientation: orientation, cropRect: cropRect) else {
            isRecognitionInProgress = false
            return
        }
        
        let visionImage = VisionImage(image: image)
        scanner.process(visionImage) { [weak self] features, error in
            defer { self?.isRecognitionInProgress = false }
            if let error = error {
                self?.delegate?.onError(error: error)
                return
            }
            guard let features = features, !features.isEmpty else {
                return
            }
            guard let barcode = features.first, let _ = barcode.rawValue else { return }
            self?.delegate?.onRecognition(result: barcode)
            self?.startDelay()
        }
    }

    func setDelay(delay: Int) {
        self.delay = delay
    }

    private func startDelay() {
        isDelayed = true
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(delay)) { [weak self] in
            self?.isDelayed = false
        }
    }
    
    func updateCropRect(cropRect: CropRect) {
        self.cropRect = cropRect
    }
}
