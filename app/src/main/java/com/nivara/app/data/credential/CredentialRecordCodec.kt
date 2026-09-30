package com.nivara.app.data.credential

import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.credential.StoredCredential
import com.nivara.app.domain.security.KeyDerivationAlgorithm
import com.nivara.app.domain.security.KeyDerivationConfig

/**
 * The on-disk form of a [StoredCredential].
 *
 * The format is a fixed, versioned byte layout rather than a serialization framework: it is
 * small, it has no dependencies, and every field is explicit, so a future change is a version
 * bump instead of a surprise.
 *
 * ```
 *  offset  size  field
 *  ------  ----  ---------------------------------------------------------------
 *       0     4  magic "NVCR"
 *       4     1  record format version (1)
 *       5     1  credential type id (see PrimaryCredentialType)
 *       6     1  key derivation algorithm id
 *       7     1  key derivation parameter version
 *       8     4  iteration count, big-endian
 *      12     1  salt length in bytes
 *      13     n  salt
 *   13+n    32  verifier
 *  45+n      4  CRC-32 of everything before it, big-endian
 * ```
 *
 * The derived key is *not* a field, because it is never written anywhere. The minimum size is 65
 * bytes. The verifier length is fixed at 32 bytes in version 1 because it is the output of
 * HMAC-SHA-256; a different construction would be a new record version.
 *
 * Decoding is strict. An unknown magic, an unknown version, an unknown type or algorithm, a
 * parameter set below the policy minimums, a length that does not match the salt it declares,
 * or a checksum mismatch all produce `null`, which the store turns into
 * [com.nivara.app.domain.credential.CredentialFailure.InvalidConfiguration]. Nothing is guessed
 * and nothing is repaired: a record that cannot be read exactly is not a record.
 */
internal object CredentialRecordCodec {

    /** Version of the record format written by this build. */
    const val FORMAT_VERSION: Int = 1

    /** Length of the verifier field in version 1: HMAC-SHA-256 output. */
    const val VERIFIER_BYTES: Int = 32

    private const val MAGIC_BYTES = 4
    private const val VERSION_INDEX = MAGIC_BYTES
    private const val TYPE_INDEX = VERSION_INDEX + 1
    private const val ALGORITHM_INDEX = TYPE_INDEX + 1
    private const val PARAMETER_VERSION_INDEX = ALGORITHM_INDEX + 1
    private const val ITERATIONS_INDEX = PARAMETER_VERSION_INDEX + 1
    private const val SALT_LENGTH_INDEX = ITERATIONS_INDEX + 4
    private const val HEADER_BYTES = SALT_LENGTH_INDEX + 1
    private const val CHECKSUM_BYTES = 4
    private const val MAXIMUM_SALT_BYTES = 0xFF

    /** Smallest possible record: header plus a minimum-length salt, verifier and checksum. */
    const val MINIMUM_BYTES: Int =
        HEADER_BYTES + KeyDerivationConfig.MINIMUM_SALT_BYTES + VERIFIER_BYTES + CHECKSUM_BYTES

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte())

    /** Encodes [credential] into its on-disk form. */
    fun encode(credential: StoredCredential): ByteArray {
        val salt = credential.salt
        require(salt.size in KeyDerivationConfig.MINIMUM_SALT_BYTES..MAXIMUM_SALT_BYTES) {
            "salt length is not representable in the record format"
        }
        require(credential.verifier.size == VERIFIER_BYTES) {
            "verifier must be $VERIFIER_BYTES bytes"
        }

        val buffer = ByteArray(HEADER_BYTES + salt.size + VERIFIER_BYTES + CHECKSUM_BYTES)
        MAGIC.copyInto(buffer, 0)
        buffer[VERSION_INDEX] = FORMAT_VERSION.toByte()
        buffer[TYPE_INDEX] = credential.type.id.toByte()
        buffer[ALGORITHM_INDEX] = credential.config.algorithm.id.toByte()
        buffer[PARAMETER_VERSION_INDEX] = credential.config.version.toByte()
        writeInt(buffer, ITERATIONS_INDEX, credential.config.iterations)
        buffer[SALT_LENGTH_INDEX] = salt.size.toByte()
        salt.copyInto(buffer, HEADER_BYTES)
        credential.verifier.copyInto(buffer, HEADER_BYTES + salt.size)
        writeInt(buffer, buffer.size - CHECKSUM_BYTES, checksum(buffer, 0, buffer.size - CHECKSUM_BYTES))
        return buffer
    }

    /** Decodes [bytes], or returns `null` when they are not a record this build can use. */
    fun decode(bytes: ByteArray): StoredCredential? {
        if (bytes.size < MINIMUM_BYTES) return null
        if (!hasMagic(bytes)) return null

        if ((bytes[VERSION_INDEX].toInt() and 0xFF) != FORMAT_VERSION) return null

        val type = PrimaryCredentialType.fromId(bytes[TYPE_INDEX].toInt() and 0xFF) ?: return null
        val algorithm = KeyDerivationAlgorithm.entries
            .firstOrNull { it.id == (bytes[ALGORITHM_INDEX].toInt() and 0xFF) }
            ?: return null
        val parameterVersion = bytes[PARAMETER_VERSION_INDEX].toInt() and 0xFF
        val iterations = readInt(bytes, ITERATIONS_INDEX)
        val saltLength = bytes[SALT_LENGTH_INDEX].toInt() and 0xFF

        if (saltLength < KeyDerivationConfig.MINIMUM_SALT_BYTES) return null
        if (iterations < algorithm.minimumIterations) return null
        if (bytes.size != HEADER_BYTES + saltLength + VERIFIER_BYTES + CHECKSUM_BYTES) return null
        if (readInt(bytes, bytes.size - CHECKSUM_BYTES) != checksum(bytes, 0, bytes.size - CHECKSUM_BYTES)) return null

        val salt = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + saltLength)
        val verifier = bytes.copyOfRange(HEADER_BYTES + saltLength, HEADER_BYTES + saltLength + VERIFIER_BYTES)

        // The parameter set is re-validated by its own constructor, which is the last line of
        // defence: whatever the file claims, an unusable parameter set must not reach the KDF.
        val config = try {
            KeyDerivationConfig(
                algorithm = algorithm,
                saltBytes = saltLength,
                iterations = iterations,
                keySizeBits = KeyDerivationConfig.SUPPORTED_KEY_SIZE_BITS,
                version = parameterVersion,
            )
        } catch (invalid: IllegalArgumentException) {
            return null
        }

        return try {
            StoredCredential(type = type, config = config, salt = salt, verifier = verifier)
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    private fun hasMagic(bytes: ByteArray): Boolean {
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return false
        }
        return true
    }
}
