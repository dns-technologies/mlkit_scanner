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

/// Barcode recognizer used by the native scanner.
class MlkitBarcodeScanner: NSObject, RecognitionHandler {
    private let scanner: BarcodeScanner
    private let analysisGate: FrameAnalysisGate
    private let cropRectLock = NSLock()
    private var cropRect: CropRect?
    private let viewId: Int64
    
    var type: RecognitionType = RecognitionType.barcodeRecognition
    weak var delegate: RecognitionResultDelegate?
    
    /// Creates a view-scoped recognizer ready to analyze its first frame immediately.
    required init(delay: Int, cropRect: CropRect?, viewId: Int64) {
        scanner = BarcodeScanner.barcodeScanner()
        analysisGate = FrameAnalysisGate(successfulScanPeriodMilliseconds: delay)
        self.cropRect = cropRect
        self.viewId = viewId
        super.init()
    }
    
    /// Attempts to recognize the first barcode in `sampleBuffer`.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation) {
        guard analysisGate.beginAnalysis() else { return }
        
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            analysisGate.completeAnalysis(barcodeFound: false)
            return
        }

        cropRectLock.lock()
        let currentCropRect = cropRect
        cropRectLock.unlock()
        let cimage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let image = UIImage(
            ciImage: cimage,
            scaleX: scaleX,
            scaleY: scaleY,
            orientation: orientation,
            cropRect: currentCropRect
        ) else {
            analysisGate.completeAnalysis(barcodeFound: false)
            return
        }
        
        let visionImage = VisionImage(image: image)
        scanner.process(visionImage) { [weak self] features, error in
            guard let self = self else { return }
            if let error = error {
                self.analysisGate.completeAnalysis(barcodeFound: false)
                self.delegate?.onError(error: error)
                return
            }
            guard let barcode = features?.first, barcode.rawValue != nil else {
                self.analysisGate.completeAnalysis(barcodeFound: false)
                return
            }
            self.analysisGate.completeAnalysis(barcodeFound: true)
            self.delegate?.onRecognition(result: barcode, viewId: self.viewId)
        }
    }

    /// Updates the cooldown applied after successful recognition.
    func setDelay(delay: Int) {
        analysisGate.updateSuccessfulScanPeriod(delay)
    }
    
    /// Updates normalized recognition geometry for future frames.
    func updateCropRect(cropRect: CropRect) {
        cropRectLock.lock()
        self.cropRect = cropRect
        cropRectLock.unlock()
    }
}
