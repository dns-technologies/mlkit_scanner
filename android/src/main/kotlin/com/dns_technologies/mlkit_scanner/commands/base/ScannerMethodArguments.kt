package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Validates untyped values received from Flutter before they reach scanner domain code. */
internal object ScannerMethodArguments {
    /** Typed arguments required to register a platform view and its initial configuration. */
    data class ViewRegistration(
        val viewId: Int,
        val initialZoomRatio: Double?,
        val initialCropRect: RecognizeVisorCropRect?,
        val initialFlashEnabled: Boolean?,
    )

    /** Typed arguments required to start barcode recognition. */
    data class ScanOptions(
        val viewId: Int,
        val periodMs: Int,
    )

    /** One typed configuration value addressed to a platform view. */
    data class ViewValue<T>(
        val viewId: Int,
        val value: T,
    )

    /** Parses view registration arguments, including optional retained controls. */
    fun viewRegistration(arguments: Any?): ViewRegistration {
        val map = requireArgumentsMap(arguments)
        return ViewRegistration(
            viewId = requireNonNegativeInt(map, PluginConstants.viewIdArgument),
            initialZoomRatio = optionalFiniteDouble(map, PluginConstants.initialZoomRatioArgument)
                ?.let(::validateZoomRatio),
            initialCropRect = optionalMap(map, PluginConstants.initialCropRectArgument)
                ?.let(::parseCropRect),
            initialFlashEnabled = optionalBoolean(map, PluginConstants.initialFlashEnabledArgument),
        )
    }

    /** Parses the platform-view identity required by view-scoped commands. */
    fun viewId(arguments: Any?): Int = requireNonNegativeInt(
        requireArgumentsMap(arguments),
        PluginConstants.viewIdArgument,
    )

    /** Parses recognition type and non-negative delay for a scan start. */
    fun scanOptions(arguments: Any?): ScanOptions {
        val map = requireArgumentsMap(arguments)
        val recognitionType = requireNonNegativeInt(map, RECOGNITION_TYPE_ARGUMENT)
        if (recognitionType != BARCODE_RECOGNITION_TYPE) throw PluginError.InvalidArguments
        return ScanOptions(
            viewId = requireNonNegativeInt(map, PluginConstants.viewIdArgument),
            periodMs = requireNonNegativeInt(map, PluginConstants.delayArgument),
        )
    }

    /** Parses view identity and a positive absolute camera zoom ratio. */
    fun zoomRatio(arguments: Any?): ViewValue<Float> {
        val map = requireArgumentsMap(arguments)
        val value = optionalFiniteDouble(map, PluginConstants.valueArgument)
            ?: throw PluginError.InvalidArguments
        return ViewValue(
            viewId = requireNonNegativeInt(map, PluginConstants.viewIdArgument),
            value = validateZoomRatio(value).toFloat(),
        )
    }

    /** Parses view identity and a non-negative cooldown after successful recognition. */
    fun scanDelay(arguments: Any?): ViewValue<Int> {
        val map = requireArgumentsMap(arguments)
        return ViewValue(
            viewId = requireNonNegativeInt(map, PluginConstants.viewIdArgument),
            value = requireNonNegativeInt(map, PluginConstants.delayArgument),
        )
    }

    /** Parses view identity and a finite crop rectangle with positive width and height scales. */
    fun cropRect(arguments: Any?): ViewValue<RecognizeVisorCropRect> {
        val map = requireArgumentsMap(arguments)
        val cropRect = optionalMap(map, PluginConstants.cropRectArgument)
            ?: throw PluginError.InvalidArguments
        return ViewValue(
            viewId = requireNonNegativeInt(map, PluginConstants.viewIdArgument),
            value = parseCropRect(cropRect),
        )
    }

    /** Converts an untyped crop map into validated normalized visor geometry. */
    private fun parseCropRect(map: Map<*, *>): RecognizeVisorCropRect {
        val scaleWidth = optionalFiniteDouble(map, SCALE_WIDTH_ARGUMENT) ?: 1.0
        val scaleHeight = optionalFiniteDouble(map, SCALE_HEIGHT_ARGUMENT) ?: 1.0
        if (scaleWidth <= 0.0 || scaleHeight <= 0.0) throw PluginError.InvalidArguments
        return RecognizeVisorCropRect(
            scaleWidth = scaleWidth,
            scaleHeight = scaleHeight,
            centerOffsetX = optionalFiniteDouble(map, OFFSET_X_ARGUMENT) ?: 0.0,
            centerOffsetY = optionalFiniteDouble(map, OFFSET_Y_ARGUMENT) ?: 0.0,
        )
    }

    /** Returns channel arguments as a map or rejects a malformed envelope. */
    private fun requireArgumentsMap(value: Any?): Map<*, *> = value as? Map<*, *>
        ?: throw PluginError.InvalidArguments

    /** Reads an optional nested map while rejecting values of another type. */
    private fun optionalMap(map: Map<*, *>, key: String): Map<*, *>? = when (val value = map[key]) {
        null -> null
        is Map<*, *> -> value
        else -> throw PluginError.InvalidArguments
    }

    /** Reads an optional finite numeric value from this map. */
    private fun optionalFiniteDouble(map: Map<*, *>, key: String): Double? = when (val value = map[key]) {
        null -> null
        is Number -> value.toDouble().takeIf(Double::isFinite)
            ?: throw PluginError.InvalidArguments
        else -> throw PluginError.InvalidArguments
    }

    /** Reads an optional Boolean value from this map. */
    private fun optionalBoolean(map: Map<*, *>, key: String): Boolean? = when (val value = map[key]) {
        null -> null
        is Boolean -> value
        else -> throw PluginError.InvalidArguments
    }

    /** Reads a required non-negative integral number that fits in an [Int]. */
    private fun requireNonNegativeInt(map: Map<*, *>, key: String): Int {
        val value = (map[key] as? Number)?.toDouble()
            ?: throw PluginError.InvalidArguments
        if (
            !value.isFinite() ||
            value % 1.0 != 0.0 ||
            value !in 0.0..Int.MAX_VALUE.toDouble()
        ) {
            throw PluginError.InvalidArguments
        }
        return value.toInt()
    }

    /** Preserves a positive zoom ratio only when its camera [Float] representation is valid. */
    private fun validateZoomRatio(value: Double): Double {
        val floatValue = value.toFloat()
        if (!floatValue.isFinite() || floatValue <= 0.0F) throw PluginError.InvalidArguments
        return value
    }

    private const val RECOGNITION_TYPE_ARGUMENT = "type"
    private const val SCALE_WIDTH_ARGUMENT = "scaleWidth"
    private const val SCALE_HEIGHT_ARGUMENT = "scaleHeight"
    private const val OFFSET_X_ARGUMENT = "offsetX"
    private const val OFFSET_Y_ARGUMENT = "offsetY"
    private const val BARCODE_RECOGNITION_TYPE = 0
}
