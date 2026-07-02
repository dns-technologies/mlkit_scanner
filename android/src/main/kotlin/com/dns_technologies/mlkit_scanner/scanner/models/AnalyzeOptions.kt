package com.dns_technologies.mlkit_scanner.scanner.models

/** Describes image recognition configuration received from Dart. */
class AnalyzeOptions(
    val periodMs: Int
) {
    companion object {
        /** Creates [AnalyzeOptions] from a method channel argument map. */
        fun fromMap(map: Map<String, Any?>): AnalyzeOptions {
            return AnalyzeOptions(
                map["delay"] as Int
            )
        }
    }
}
