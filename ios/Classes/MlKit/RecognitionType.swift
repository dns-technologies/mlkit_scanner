//
//  RecognitionType.swift
//  GoogleDataTransport
//
//  Created by ООО "ДНС Технологии" on 10.03.2021.
//

import Foundation

/// Object recognition modes supported by the native plugin.
enum RecognitionType: Int {
    /// Barcode recognition.
    case barcodeRecognition = 0
}

extension RecognitionType {
    /// Creates a view-scoped handler for this recognition mode.
    func createRecognitionHandler(delay: Int, cropRect: CropRect?, viewId: Int64) -> RecognitionHandler {
        switch self {
        case .barcodeRecognition:
            return MlkitBarcodeScanner(delay: delay, cropRect: cropRect, viewId: viewId)
        }
    }
}
