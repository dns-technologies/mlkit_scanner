package com.dns_technologies.mlkit_scanner.utils

/** Collects exceptions from independent actions; use one instance per batch on a single thread. */
internal class ExceptionCollector(initialFailure: Exception? = null) {
    /** First failure remains primary; repeated exception instances are never self-suppressed. */
    private var failure: Exception? = initialFailure

    /** Attempts one step even when an earlier independent step failed. */
    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
    }

    /** Throws the first collected exception, with later exceptions attached as suppressed causes. */
    fun throwIfFailed() { failure?.let { throw it } }
}
