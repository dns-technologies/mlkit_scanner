//
//  RecognitionType.swift
//  GoogleDataTransport
//
//  Created by ООО "ДНС Технологии" on 10.03.2021.
//

import Foundation

/// Recognition types for objects
enum RecognitionType: Int {
    /// Barcode recognition
    case barcodeRecognition = 0
}

extension RecognitionType {
    /// Creation of RecognitionHandler by RecognitionType.
    /// `delay` - delay between detections
    /// `cropRect` optional `CropRect`, area of the detection.
    func createRecognitionHandler(delay: Int, cropRect: CropRect?) -> RecognitionHandler {
        switch self {
        case .barcodeRecognition:
            return MlkitBarcodeScanner(delay: delay, cropRect: cropRect)
        }
    }
}
