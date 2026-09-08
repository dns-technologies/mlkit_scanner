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
    /// Protected with cropRectLock; frame submissions snapshot this actual subscription.
    private var subscription: ScanResultSubscription?
    
    var type: RecognitionType = RecognitionType.barcodeRecognition
    
    /// Creates a reusable recognizer ready to analyze its first frame immediately.
    init(delay: Int, cropRect: CropRect?) {
        scanner = BarcodeScanner.barcodeScanner()
        analysisGate = FrameAnalysisGate(successfulScanPeriodMilliseconds: delay)
        self.cropRect = cropRect
        super.init()
    }
    
    /// Attempts to recognize the first barcode in `sampleBuffer`.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation) {
        cropRectLock.lock()
        let listener = subscription
        let currentCropRect = cropRect
        cropRectLock.unlock()
        guard let listener = listener, analysisGate.beginAnalysis() else { return }
        
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            analysisGate.completeAnalysis(barcodeFound: false)
            return
        }

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
            if error != nil {
                self.analysisGate.completeAnalysis(barcodeFound: false)
                return
            }
            guard let barcode = features?.first, barcode.rawValue != nil else {
                self.analysisGate.completeAnalysis(barcodeFound: false)
                return
            }
            self.analysisGate.completeAnalysis(barcodeFound: true)
            DispatchQueue.main.async { listener.deliver(barcode) }
        }
    }

    /// Installs a real result listener; an old analysis cannot be reassigned to a new subscription.
    func subscribe(_ onResult: @escaping (Barcode) -> Void) -> ScanResultSubscription {
        unsubscribe()
        let listener = ScanResultSubscription(onResult)
        cropRectLock.lock()
        subscription = listener
        cropRectLock.unlock()
        return listener
    }

    /// Called on main; cancellation also suppresses a previously queued main-thread delivery.
    func unsubscribe() {
        cropRectLock.lock()
        let previous = subscription
        subscription = nil
        cropRectLock.unlock()
        previous?.cancel()
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
