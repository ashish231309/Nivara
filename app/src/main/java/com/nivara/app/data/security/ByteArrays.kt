package com.nivara.app.data.security

/**
 * `true` when this array begins with [prefix].
 *
 * Kotlin's standard library provides `startsWith` for strings, not for byte arrays, and the
 * security code parses byte-level formats, so the comparison lives here once instead of being
 * open-coded in every parser. The arrays compared are format magics, which are public constants
 * and not secret, so an early-exit comparison is appropriate.
 */
internal fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}
