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
    
    private var useDoubleVerification = false
    private var isVerificationInProgress = false
    private var verificationIteration = 0
    private var verifyingBarcodeValue: String?
    
    // Specifies the number of consecutive frames to analyze for barcode data confirmation.
    // Increasing this value affects performance
    private let barcodeVerificationFrameCount = 2
    
    var type: RecognitionType = .barcodeRecognition
    weak var delegate: RecognitionResultDelegate?
    
    /// Can the barcode be recognized.
    ///
    /// Used for optimization so as not to recognize the barcode on every frame.
    var canRecognize: Bool {
        !isDelayed && !isRecognitionInProgress
    }
    
    required init(delay: Int, useDoubleVerification: Bool, cropRect: CropRect?) {
        scanner = BarcodeScanner.barcodeScanner()
        self.delay = delay
        self.cropRect = cropRect
        self.useDoubleVerification = useDoubleVerification
        super.init()
        startDelay()
    }
    
    /// Recognizes a barcode on frame [sampleBuffer].
    func processVideoOutput(sampleBuffer: CMSampleBuffer, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation) {
        guard canRecognize else { return }
        
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
            guard let self = self else { return }
            defer { self.isRecognitionInProgress = false }
            
            if let error = error {
                self.delegate?.onError(error: error)
                return
            }
            
            guard let features = features, !features.isEmpty else { return }
            guard let barcode = features.first, let rawValue = barcode.rawValue else { return }
            
            if self.useDoubleVerification, self.shouldVerifyBarcode(barcode), !self.isVerificationInProgress {
                self.verifyingBarcodeValue = rawValue
                self.isVerificationInProgress = true
            }
            
            if self.isVerificationInProgress {
                self.verificationIteration += 1
                
                guard rawValue == self.verifyingBarcodeValue else {
                    self.resetBarcodeVerification()
                    return
                }
                
                // Verification passed - reset and send result
                if self.verificationIteration >= self.barcodeVerificationFrameCount {
                    self.resetBarcodeVerification()
                }
            }
            
            // Send result if not in verification process or verification passed
            if !self.isVerificationInProgress {
                self.delegate?.onRecognition(result: barcode)
                self.startDelay()
            }
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
    
    /// Determines if the barcode should be verified
    private func shouldVerifyBarcode(_ barcode: Barcode) -> Bool {
        switch barcode.format {
        case .code128, .code39, .code93, .codaBar, .EAN13, .EAN8, .ITF, .UPCA, .UPCE:
            return true
        default:
            return false
        }
    }
    
    /// Resets the barcode verification state
    private func resetBarcodeVerification() {
        verifyingBarcodeValue = nil
        verificationIteration = 0
        isVerificationInProgress = false
    }
}