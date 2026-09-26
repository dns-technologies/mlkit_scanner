//
//  MlkitBarcodeScanner.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 05.03.2021.
//

import Foundation
import CoreImage
import UIKit
import AVFoundation
import MLKitBarcodeScanning
import MLKitVision

/// Barcode recognizer used by the native scanner.
class MlkitBarcodeScanner: NSObject, RecognitionHandler, BarcodeAnalyzing {
    /// ML Kit recognizer reused across camera frames.
    private let scanner: BarcodeScanner
    /// Limits concurrent analyses and enforces result-dependent cooldowns.
    private let analysisGate: FrameAnalysisGate
    /// Protects crop geometry and the current result subscription.
    private let cropRectLock = NSLock()
    /// Core Image context reused to crop submitted frames.
    private let imageContext = CIContext()
    /// Optional normalized recognition area; protected by cropRectLock.
    private var cropRect: CropRect?
    /// Protected with cropRectLock; frame submissions snapshot this actual subscription.
    private var subscription: ScanResultSubscription?

    /// Recognition mode supported by this endpoint.
    var type: RecognitionType = RecognitionType.barcodeRecognition

    /// Creates a reusable recognizer ready to analyze its first frame immediately.
    init(delay: Int, cropRect: CropRect?) {
        scanner = BarcodeScanner.barcodeScanner()
        analysisGate = FrameAnalysisGate(successfulScanPeriodMilliseconds: delay)
        self.cropRect = cropRect
        super.init()
    }

    /// Attempts to recognize the first barcode in `sampleBuffer`.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize) {
        cropRectLock.lock()
        let listener = subscription
        cropRectLock.unlock()
        guard let listener = listener else { return }
        processVideoOutput(sampleBuffer: sampleBuffer, viewport: viewport, listener: listener)
    }

    /// Creates a frame endpoint bound to one concrete result subscription.
    func input(for listener: ScanResultSubscription) -> RecognitionHandler {
        ScanInput(scanner: self, listener: listener)
    }

    /// Submits a frame using the captured listener and viewport geometry.
    fileprivate func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize, listener: ScanResultSubscription) {
        cropRectLock.lock()
        let currentCropRect = cropRect
        cropRectLock.unlock()
        guard analysisGate.beginAnalysis() else { return }

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            analysisGate.completeAnalysis(barcodeFound: false)
            return
        }

        let cimage = CIImage(cvPixelBuffer: pixelBuffer)
        let geometry = CameraFrameGeometry(source: cimage.extent.size, viewport: viewport)
        let visible = geometry.recognitionBounds(currentCropRect)
        guard !visible.isNull, !visible.isEmpty else {
            analysisGate.completeAnalysis(barcodeFound: false)
            return
        }
        // CIImage coordinates grow upward; Flutter and the video buffer use top-left geometry.
        let crop = CGRect(x: visible.minX, y: cimage.extent.height - visible.maxY,
            width: visible.width, height: visible.height)
        guard let cgImage = imageContext.createCGImage(cimage, from: crop) else {
            analysisGate.completeAnalysis(barcodeFound: false)
            return
        }
        let image = UIImage(cgImage: cgImage)
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
            let result = barcode.scannerBarcode
            DispatchQueue.main.async { listener.deliver(result) }
        }
    }

    /// Installs a real result listener; an old analysis cannot be reassigned to a new subscription.
    func subscribe(_ onResult: @escaping (ScannerBarcode) -> Void) -> ScanResultSubscription {
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

/// The receiver snapshots this endpoint before crossing into the analysis queue.
private final class ScanInput: RecognitionHandler {
    /// Weak reference to the shared recognizer receiving captured frames.
    private weak var scanner: MlkitBarcodeScanner?
    /// Concrete result subscription retained across analysis queue submission.
    private let listener: ScanResultSubscription
    /// Recognition mode supported by this endpoint.
    var type: RecognitionType = .barcodeRecognition

    /// Binds a recognizer to the subscription captured by a camera receiver.
    init(scanner: MlkitBarcodeScanner, listener: ScanResultSubscription) {
        self.scanner = scanner
        self.listener = listener
    }

    /// Submits a frame using the captured listener and viewport geometry.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize) {
        scanner?.processVideoOutput(sampleBuffer: sampleBuffer, viewport: viewport, listener: listener)
    }
}
