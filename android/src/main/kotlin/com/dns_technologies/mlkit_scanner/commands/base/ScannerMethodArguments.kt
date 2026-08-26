package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Validates untyped values received from Flutter before they reach scanner domain code. */
internal object ScannerMethodArguments {
    /** Typed arguments required to initialize a platform-view camera. */
    data class CameraInitialization(
        val viewId: Int,
        val initialZoom: Double?,
        val initialCropRect: RecognizeVisorCropRect?,
    )

    /** Typed arguments required to start barcode recognition. */
    data class ScanOptions(
        val periodMs: Int,
    )

    /** Parses camera initialization arguments, including optional startup controls. */
    fun cameraInitialization(arguments: Any?): CameraInitialization {
        val map = arguments.requireMap()
        return CameraInitialization(
            viewId = map.requireInt(PluginConstants.viewIdArgument, minimum = 0),
            initialZoom = map.optionalFiniteDouble(PluginConstants.initialZoomArgument)
                ?.requireInRange(MIN_ZOOM, MAX_ZOOM),
            initialCropRect = map.optionalMap(PluginConstants.initialCropRectArgument)
                ?.let(::cropRect),
        )
    }

    /** Parses the platform-view identity required by view-scoped commands. */
    fun viewId(arguments: Any?): Int = arguments.requireMap()
        .requireInt(PluginConstants.viewIdArgument, minimum = 0)

    /** Parses recognition type and non-negative delay for a scan start. */
    fun scanOptions(arguments: Any?): ScanOptions {
        val map = arguments.requireMap()
        val recognitionType = map.requireInt(RECOGNITION_TYPE_ARGUMENT, minimum = 0)
        if (recognitionType != BARCODE_RECOGNITION_TYPE) throw PluginError.InvalidArguments
        return ScanOptions(
            periodMs = map.requireInt(SCAN_DELAY_ARGUMENT, minimum = 0),
        )
    }

    /** Parses normalized linear camera zoom in the CameraX-supported range. */
    fun zoom(arguments: Any?): Float = arguments.requireFiniteDouble()
        .requireInRange(MIN_ZOOM, MAX_ZOOM)
        .toFloat()

    /** Parses a non-negative delay between recognition attempts. */
    fun scanDelay(arguments: Any?): Int = arguments.requireInt(minimum = 0)

    /** Parses a finite crop rectangle with positive width and height scales. */
    fun cropRect(arguments: Any?): RecognizeVisorCropRect = cropRect(arguments.requireMap())

    private fun cropRect(map: Map<*, *>): RecognizeVisorCropRect {
        val scaleWidth = map.optionalFiniteDouble(SCALE_WIDTH_ARGUMENT) ?: DEFAULT_SCALE
        val scaleHeight = map.optionalFiniteDouble(SCALE_HEIGHT_ARGUMENT) ?: DEFAULT_SCALE
        if (scaleWidth <= 0.0 || scaleHeight <= 0.0) throw PluginError.InvalidArguments
        return RecognizeVisorCropRect(
            scaleWidth = scaleWidth,
            scaleHeight = scaleHeight,
            centerOffsetX = map.optionalFiniteDouble(OFFSET_X_ARGUMENT) ?: DEFAULT_OFFSET,
            centerOffsetY = map.optionalFiniteDouble(OFFSET_Y_ARGUMENT) ?: DEFAULT_OFFSET,
        )
    }

    private fun Any?.requireMap(): Map<*, *> = this as? Map<*, *>
        ?: throw PluginError.InvalidArguments

    private fun Map<*, *>.optionalMap(key: String): Map<*, *>? = when (val value = this[key]) {
        null -> null
        is Map<*, *> -> value
        else -> throw PluginError.InvalidArguments
    }

    private fun Map<*, *>.optionalFiniteDouble(key: String): Double? = when (val value = this[key]) {
        null -> null
        else -> value.requireFiniteDouble()
    }

    private fun Map<*, *>.requireInt(key: String, minimum: Int): Int =
        this[key].requireInt(minimum)

    private fun Any?.requireFiniteDouble(): Double {
        val value = (this as? Number)?.toDouble() ?: throw PluginError.InvalidArguments
        if (!value.isFinite()) throw PluginError.InvalidArguments
        return value
    }

    private fun Any?.requireInt(minimum: Int): Int {
        val value = when (this) {
            is Byte -> toInt()
            is Short -> toInt()
            is Int -> this
            is Long -> if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
            is Float -> if (
                isFinite() &&
                toDouble() >= Int.MIN_VALUE.toDouble() &&
                toDouble() <= Int.MAX_VALUE.toDouble() &&
                this % 1.0F == 0.0F
            ) {
                toInt()
            } else {
                null
            }
            is Double -> if (
                isFinite() &&
                this >= Int.MIN_VALUE.toDouble() &&
                this <= Int.MAX_VALUE.toDouble() &&
                this % 1.0 == 0.0
            ) {
                toInt()
            } else {
                null
            }
            else -> null
        } ?: throw PluginError.InvalidArguments
        if (value < minimum) throw PluginError.InvalidArguments
        return value
    }

    private fun Double.requireInRange(minimum: Double, maximum: Double): Double {
        if (this !in minimum..maximum) throw PluginError.InvalidArguments
        return this
    }

    private const val RECOGNITION_TYPE_ARGUMENT = "type"
    private const val SCAN_DELAY_ARGUMENT = "delay"
    private const val SCALE_WIDTH_ARGUMENT = "scaleWidth"
    private const val SCALE_HEIGHT_ARGUMENT = "scaleHeight"
    private const val OFFSET_X_ARGUMENT = "offsetX"
    private const val OFFSET_Y_ARGUMENT = "offsetY"
    private const val BARCODE_RECOGNITION_TYPE = 0
    private const val MIN_ZOOM = 0.0
    private const val MAX_ZOOM = 1.0
    private const val DEFAULT_SCALE = 1.0
    private const val DEFAULT_OFFSET = 0.0
}
