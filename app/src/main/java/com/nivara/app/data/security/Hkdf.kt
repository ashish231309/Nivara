package com.nivara.app.data.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) with HMAC-SHA-256.
 *
 * Used to turn one high-entropy key into several independent subkeys, and to derive the keystream
 * that protects a key being wrapped. HKDF is the right tool here because its inputs are already
 * uniformly random (a vault key, a recovery key): it provides domain separation and expansion, not
 * password hardening — that is PBKDF2's job.
 *
 * The implementation is deliberately small and readable; HMAC-SHA-256 comes from the platform
 * provider, so no cryptographic primitive is implemented here.
 */
internal object Hkdf {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32
    private const val MAX_OUTPUT_LENGTH = 255 * HASH_LENGTH

    /**
     * Derives [length] bytes from [ikm] (input key material).
     *
     * [salt] must be unique per use for a given [ikm]: reusing a salt produces the same output,
     * which for the wrapping construction below would mean reusing a keystream. Callers pass a
     * fresh random nonce.
     *
     * [info] separates independent uses of the same key. Two derivations with different info
     * strings are computationally unrelated.
     */
    fun deriveKey(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val pseudorandomKey = extract(salt = salt, ikm = ikm)
        return try {
            expand(pseudorandomKey = pseudorandomKey, info = info, length = length)
        } finally {
            pseudorandomKey.fill(0)
        }
    }

    /** RFC 5869 step 1: `PRK = HMAC-Hash(salt, IKM)`. */
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        // An empty salt is defined to mean a string of HashLen zero bytes.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 step 2: repeated `T(n) = HMAC-Hash(PRK, T(n-1) | info | n)`. */
    private fun expand(pseudorandomKey: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_OUTPUT_LENGTH) { "HKDF output length out of range: $length" }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pseudorandomKey, HMAC_ALGORITHM))

        val output = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            // The previous block has been consumed by the MAC; clear it before replacing it.
            block.fill(0)
            block = mac.doFinal()

            val copied = minOf(block.size, length - offset)
            block.copyInto(output, offset, 0, copied)
            offset += copied
            counter++
        }
        block.fill(0)
        return output
    }

    /** HMAC-SHA-256 over [data] under [key], used for the authentication tag of sealed keys. */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }
}
