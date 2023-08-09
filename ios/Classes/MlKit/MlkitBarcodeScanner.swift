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
    private var isAnalysisInProgress = false
    
    var type: RecognitionType = RecognitionType.barcodeRecognition
    weak var delegate: RecognitionResultDelegate?
    
    required init(delay: Int, cropRect: CropRect?) {
        scanner = BarcodeScanner.barcodeScanner()
        self.delay = delay
        self.cropRect = cropRect
        super.init()
        startDelay()
    }
    
    func proccessVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation) {
        if (!canAnalyze()) {
            return
        }
        isAnalysisInProgress = true
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let cimage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let image = UIImage(ciImage: cimage, scaleX: scaleX, scaleY: scaleY, orientation: orientation, cropRect: cropRect) else {
            return
        }
        let visionImage = VisionImage(image: image)
        scanner.process(visionImage) { [weak self] features, error in
            if let error = error {
                self?.delegate?.onError(error: error)
                self?.isAnalysisInProgress = false
                return
            }
            guard let features = features, !features.isEmpty else {
                self?.isAnalysisInProgress = false
                return
            }
            guard let barcode = features.first, let _ = barcode.rawValue else { return }
            self?.delegate?.onRecognition(result: barcode)
            self?.isAnalysisInProgress = false
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
    
    private func canAnalyze() -> Bool {
        return !isDelayed && !isAnalysisInProgress
    }
    
    func updateCropRect(cropRect: CropRect) {
        self.cropRect = cropRect
    }
}
