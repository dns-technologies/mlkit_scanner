package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.models

/** Enum describes what exactly will be recognized from the camera image */
enum class RecognitionType(val typeCode: Int) {
    BarcodeRecognition(0);

    companion object {
        fun fromValue(code: Int): RecognitionType {
            return values().first { it.typeCode == code }
        }
    }
}