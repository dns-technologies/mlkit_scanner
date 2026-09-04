package com.dns_technologies.mlkit_scanner.utils

import com.dns_technologies.mlkit_scanner.PluginError

internal fun Any?.requireMap(): Map<*, *> = this as? Map<*, *>
    ?: throw PluginError.InvalidArguments

internal fun Map<*, *>.optionalMap(key: String): Map<*, *>? = when (val value = this[key]) {
    null -> null
    is Map<*, *> -> value
    else -> throw PluginError.InvalidArguments
}

internal fun Map<*, *>.requireMap(key: String): Map<*, *> =
    optionalMap(key) ?: throw PluginError.InvalidArguments

internal fun Map<*, *>.optionalBoolean(key: String): Boolean? = when (val value = this[key]) {
    null -> null
    is Boolean -> value
    else -> throw PluginError.InvalidArguments
}

internal fun Map<*, *>.optionalFiniteDouble(key: String): Double? = when (val value = this[key]) {
    null -> null
    else -> value.requireFiniteDouble()
}

internal fun Map<*, *>.requireFiniteDouble(key: String): Double =
    this[key].requireFiniteDouble()

internal fun Map<*, *>.requireInt(key: String): Int =
    (this[key] as? Number)?.toInt()
        ?: throw PluginError.InvalidArguments

private fun Any?.requireFiniteDouble(): Double {
    val value = (this as? Number)?.toDouble() ?: throw PluginError.InvalidArguments
    return value.takeIf(Double::isFinite) ?: throw PluginError.InvalidArguments
}
