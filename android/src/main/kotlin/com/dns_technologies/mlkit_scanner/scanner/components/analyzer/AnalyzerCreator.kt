package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.models.RecognitionType

/** Implementing a camera analyzer simple factory depending on the [RecognitionType] */
class AnalyzerCreator {
    companion object {
        fun create(type: RecognitionType): CameraImageAnalyzer {
            return when (type) {
                RecognitionType.BarcodeRecognition -> MlSingleBarcodeAnalyzer(type)
            }
        }
    }
}
