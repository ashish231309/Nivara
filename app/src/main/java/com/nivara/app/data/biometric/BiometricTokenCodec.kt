package com.nivara.app.data.biometric

import com.nivara.app.data.credential.checksum
import com.nivara.app.data.credential.readInt
import com.nivara.app.data.credential.writeInt
import com.nivara.app.data.security.EncryptedEnvelope
import com.nivara.app.domain.security.SecureRandomGenerator

/**
 * The non-secret half of Nivara's biometric configuration.
 *
 * [ciphertext] is a random token that only the Keystore-bound biometric key can decrypt, together
 * with the IV the platform generated for that one operation. Neither value is a key: the token is
 * never used to encrypt anything, and the key itself lives in the platform key store and cannot be
 * exported. Possessing this file is worth nothing — decrypting it requires the platform to accept
 * the user's biometric first.
 *
 * @param iv the nonce the platform generated when the token was encrypted.
 * @param ciphertext the encrypted token, including its authentication tag.
 */
internal class BiometricToken(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * The on-disk form of a [BiometricToken].
 *
 * ```
 *  offset  size  field
 *  ------  ----  ------------------------------------------------
 *       0     4  magic "NVBT"
 *       4     1  format version (1)
 *       5     1  IV length
 *       6     n  IV
 *    6+n      1  ciphertext length
 *    7+n      m  ciphertext
 *  7+n+m      4  CRC-32 of everything before it, big-endian
 * ```
 *
 * Both lengths are stored and checked exactly, so a file written by a different construction — a
 * different nonce size, a different tag size, a truncated write — is rejected rather than guessed
 * at. Version 1 therefore always encodes to [LENGTH_BYTES] bytes. The checksum detects accidental
 * corruption only; it is not a MAC, and it does not need to be, because the contents are protected
 * by the authenticated encryption under the biometric key.
 */
internal object BiometricTokenCodec {

    /** Version of the token format written by this build. */
    const val FORMAT_VERSION: Int = 1

    /** Nonce length used by the biometric key's AES-GCM operation. */
    const val IV_BYTES: Int = EncryptedEnvelope.NONCE_LENGTH

    /** Token plaintext length plus the GCM authentication tag. */
    const val CIPHERTEXT_BYTES: Int = SecureRandomGenerator.KEY_SIZE_BYTES + EncryptedEnvelope.TAG_LENGTH_BYTES

    private const val MAGIC_BYTES = 4
    private const val VERSION_INDEX = MAGIC_BYTES
    private const val IV_LENGTH_INDEX = VERSION_INDEX + 1
    private const val IV_INDEX = IV_LENGTH_INDEX + 1
    private const val CIPHERTEXT_LENGTH_INDEX = IV_INDEX + IV_BYTES
    private const val CIPHERTEXT_INDEX = CIPHERTEXT_LENGTH_INDEX + 1
    private const val CHECKSUM_BYTES = 4

    /** Fixed size of the encoded form. */
    const val LENGTH_BYTES: Int = CIPHERTEXT_INDEX + CIPHERTEXT_BYTES + CHECKSUM_BYTES

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte())

    /** Encodes [token] into its on-disk form. */
    fun encode(token: BiometricToken): ByteArray {
        require(token.iv.size == IV_BYTES) { "IV must be $IV_BYTES bytes" }
        require(token.ciphertext.size == CIPHERTEXT_BYTES) { "ciphertext must be $CIPHERTEXT_BYTES bytes" }

        val buffer = ByteArray(LENGTH_BYTES)
        MAGIC.copyInto(buffer, 0)
        buffer[VERSION_INDEX] = FORMAT_VERSION.toByte()
        buffer[IV_LENGTH_INDEX] = IV_BYTES.toByte()
        token.iv.copyInto(buffer, IV_INDEX)
        buffer[CIPHERTEXT_LENGTH_INDEX] = CIPHERTEXT_BYTES.toByte()
        token.ciphertext.copyInto(buffer, CIPHERTEXT_INDEX)
        writeInt(buffer, LENGTH_BYTES - CHECKSUM_BYTES, checksum(buffer, 0, LENGTH_BYTES - CHECKSUM_BYTES))
        return buffer
    }

    /** Decodes [bytes], or returns `null` when they are not a token file this build wrote. */
    fun decode(bytes: ByteArray): BiometricToken? {
        if (bytes.size != LENGTH_BYTES) return null
        if (!hasMagic(bytes)) return null
        if ((bytes[VERSION_INDEX].toInt() and 0xFF) != FORMAT_VERSION) return null
        if ((bytes[IV_LENGTH_INDEX].toInt() and 0xFF) != IV_BYTES) return null
        if ((bytes[CIPHERTEXT_LENGTH_INDEX].toInt() and 0xFF) != CIPHERTEXT_BYTES) return null
        if (readInt(bytes, LENGTH_BYTES - CHECKSUM_BYTES) != checksum(bytes, 0, LENGTH_BYTES - CHECKSUM_BYTES)) {
            return null
        }

        return BiometricToken(
            iv = bytes.copyOfRange(IV_INDEX, IV_INDEX + IV_BYTES),
            ciphertext = bytes.copyOfRange(CIPHERTEXT_INDEX, CIPHERTEXT_INDEX + CIPHERTEXT_BYTES),
        )
    }

    private fun hasMagic(bytes: ByteArray): Boolean {
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return false
        }
        return true
    }
}
