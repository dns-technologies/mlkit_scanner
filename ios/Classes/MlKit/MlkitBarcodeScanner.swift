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

/// Recognizes barcodes from camera frames using ML Kit.
/// Accepts the first available frame of each subscription without an initial cooldown.
class MlkitBarcodeScanner: NSObject, RecognitionHandler, BarcodeAnalyzing {
    /// ML Kit recognizer reused across camera frames.
    private let scanner: BarcodeScanner
    /// Limits concurrent analyses and enforces result-dependent cooldowns.
    private let analysisGate: FrameAnalysisGate
    /// Protects crop geometry and the current result subscription.
    private let stateLock = NSLock()
    /// Core Image context reused to crop submitted frames.
    private let imageContext = CIContext()
    /// Optional normalized recognition area; protected by stateLock.
    private var cropRect: CropRect?
    /// Active listener eligible to submit frames; protected by stateLock.
    private var subscription: ScanResultSubscription?

    init(delay: Int, cropRect: CropRect?) {
        scanner = BarcodeScanner.barcodeScanner()
        analysisGate = FrameAnalysisGate(successfulScanPeriodMilliseconds: delay)
        self.cropRect = cropRect
        super.init()
    }

    /// Attempts to recognize the first barcode with a raw value in `sampleBuffer`.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize) {
        stateLock.lock()
        let listener = subscription
        stateLock.unlock()
        guard let listener = listener else { return }
        processVideoOutput(sampleBuffer: sampleBuffer, viewport: viewport, listener: listener)
    }

    /// Creates a frame endpoint bound to one concrete result subscription.
    func input(for listener: ScanResultSubscription) -> RecognitionHandler {
        ScanInput(scanner: self, listener: listener)
    }

    /// Analyzes a frame if its subscription is still current and eligible.
    fileprivate func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize, listener: ScanResultSubscription) {
        stateLock.lock()
        guard subscription === listener, analysisGate.beginAnalysis() else {
            stateLock.unlock()
            return
        }
        let currentCropRect = cropRect
        stateLock.unlock()

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
        // CIImage coordinates grow upward; recognition bounds use a top-left origin.
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
            guard let barcode = features?.first(where: { $0.rawValue != nil }) else {
                self.analysisGate.completeAnalysis(barcodeFound: false)
                return
            }
            self.analysisGate.completeAnalysis(barcodeFound: true)
            let result = barcode.scannerBarcode
            DispatchQueue.main.async { listener.deliver(result) }
        }
    }

    /// Subscribes to future recognition results, cancelling the previous subscription.
    func subscribe(_ onResult: @escaping (ScannerBarcode) -> Void) -> ScanResultSubscription {
        let listener = ScanResultSubscription(onResult)
        replaceSubscription(with: listener)
        return listener
    }

    /// Called on main; cancellation also suppresses a previously queued main-thread delivery.
    func unsubscribe() {
        replaceSubscription(with: nil)
    }

    /// Updates the cooldown applied after successful recognition.
    func setDelay(delay: Int) {
        analysisGate.updateSuccessfulScanPeriod(delay)
    }

    /// Updates normalized recognition geometry for future frames.
    func updateCropRect(cropRect: CropRect) {
        stateLock.lock()
        self.cropRect = cropRect
        stateLock.unlock()
    }

    /// Replaces frame admission and its cooldown together, then revokes obsolete result delivery.
    private func replaceSubscription(with listener: ScanResultSubscription?) {
        stateLock.lock()
        let previous = subscription
        subscription = listener
        analysisGate.resetCooldown()
        stateLock.unlock()
        previous?.cancel()
    }
}

/// Recognition endpoint binding a recognizer to one retained result subscription.
private final class ScanInput: RecognitionHandler {
    /// Weak reference to the shared recognizer receiving captured frames.
    private weak var scanner: MlkitBarcodeScanner?
    /// Concrete result subscription retained across analysis queue submission.
    private let listener: ScanResultSubscription

    init(scanner: MlkitBarcodeScanner, listener: ScanResultSubscription) {
        self.scanner = scanner
        self.listener = listener
    }

    /// Submits a frame using the captured listener and viewport geometry.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize) {
        scanner?.processVideoOutput(sampleBuffer: sampleBuffer, viewport: viewport, listener: listener)
    }
}
