package com.dns_technologies.mlkit_scanner.models

/** Describes image recognition configuration  */
class AnalyzeOptions(
    val recognizeType: RecognitionType,
    val periodMs: Int,
    val useDoubleVerification: Boolean
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): AnalyzeOptions {
            return AnalyzeOptions(
                RecognitionType.fromValue(map["type"] as Int),
                map["delay"] as Int,
                map["useDoubleVerification"] as Boolean
            )
        }
    }
}