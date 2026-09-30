package com.nivara.app.testing

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SensitiveBytes

/**
 * Test-only helpers shared by the cryptography tests.
 *
 * These exist so the tests can express intent (hex fixtures, typed failures, key material) without
 * duplicating conversion code in every file.
 */

/** Decodes a hexadecimal string into bytes. Used for known-answer vectors. */
internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
    }
}

/**
 * Encodes bytes as lowercase hexadecimal, for comparison against published vectors.
 *
 * Written with an explicit unsigned conversion so the result cannot depend on how a formatter
 * treats a signed byte.
 */
internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0') }

/** Fresh random bytes straight from the platform's secure random source. */
internal fun randomBytes(size: Int): ByteArray = SecureRandomGenerator().nextByteArray(size)

/** A 256-bit in-process key holding [bytes]. */
internal fun keyOf(bytes: ByteArray, label: String = "test-key"): EncryptionKey =
    EncryptionKey.fromRawBytes(SensitiveBytes.of(bytes), label)

/** A random 256-bit in-process key. */
internal fun randomKey(label: String = "test-key"): EncryptionKey =
    keyOf(randomBytes(SecureRandomGenerator.KEY_SIZE_BYTES), label)

/** The raw material of an in-process key, for assertions. */
internal fun EncryptionKey.material(): ByteArray =
    (this as EncryptionKey.InProcess).material.copyBytes()

/** The value of a successful result, failing the test with a readable message otherwise. */
@Suppress("UNCHECKED_CAST")
internal fun <T> NivaraResult<T>.valueOrFail(): T =
    (this as? NivaraResult.Success<T>)?.value ?: error("expected a successful result but was $this")

/** The typed cryptographic failure of a failed result, or `null` when it succeeded. */
internal fun NivaraResult<*>.cryptographicFailure(): CryptographicFailure? =
    (this as? NivaraResult.Failure)?.error as? CryptographicFailure
