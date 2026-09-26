package com.dns_technologies.mlkit_scanner.utils

import com.dns_technologies.mlkit_scanner.PluginError

/** Requires a codec map or reports the plugin's invalid-arguments error. */
internal fun Any?.requireMap(): Map<*, *> = this as? Map<*, *> ?: throw PluginError.InvalidArguments

/** Reads an optional nested map, rejecting values of another type. */
internal fun Map<*, *>.optionalMap(key: String): Map<*, *>? =
    when (val value = this[key]) {
        null -> null
        is Map<*, *> -> value
        else -> throw PluginError.InvalidArguments
    }

/** Requires a nested map under the requested channel key. */
internal fun Map<*, *>.requireMap(key: String): Map<*, *> =
    optionalMap(key) ?: throw PluginError.InvalidArguments

/** Reads an optional Boolean without coercing numbers or strings. */
internal fun Map<*, *>.optionalBoolean(key: String): Boolean? =
    when (val value = this[key]) {
        null -> null
        is Boolean -> value
        else -> throw PluginError.InvalidArguments
    }

/** Requires a Boolean under the requested channel key. */
internal fun Map<*, *>.requireBoolean(key: String): Boolean =
    optionalBoolean(key) ?: throw PluginError.InvalidArguments

/** Reads an optional numeric value while rejecting NaN and infinities. */
internal fun Map<*, *>.optionalFiniteDouble(key: String): Double? =
    when (val value = this[key]) {
        null -> null
        else -> value.requireFiniteDouble()
    }

/** Requires a finite numeric value under the requested channel key. */
internal fun Map<*, *>.requireFiniteDouble(key: String): Double = this[key].requireFiniteDouble()

/** Converts a codec number to Float without producing infinity or underflowing to zero. */
internal fun Map<*, *>.requireFiniteFloat(key: String): Float {
    val number = requireFiniteDouble(key)
    val value = number.toFloat()
    return value.takeIf { it.isFinite() && (it != 0F || number == 0.0) }
        ?: throw PluginError.InvalidArguments
}

/** Requires an exactly representable Int without truncation or overflow. */
internal fun Map<*, *>.requireInt(key: String): Int {
    val value = this[key].requireFiniteDouble()
    return value.toInt().takeIf { it.toDouble() == value } ?: throw PluginError.InvalidArguments
}

/** Converts codec numbers to Double and rejects absent or non-finite values. */
private fun Any?.requireFiniteDouble(): Double {
    val value = (this as? Number)?.toDouble() ?: throw PluginError.InvalidArguments
    return value.takeIf(Double::isFinite) ?: throw PluginError.InvalidArguments
}
