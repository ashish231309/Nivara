package com.nivara.app.data.credential

import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.credential.StoredCredential
import com.nivara.app.domain.security.KeyDerivationAlgorithm
import com.nivara.app.domain.security.KeyDerivationConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the on-disk credential record.
 *
 * Decoding is strict on purpose: a record that cannot be read exactly is not repaired, not
 * partially used and not upgraded on the fly. Every rejection below ends as "no usable
 * configuration" rather than as a subtly different credential.
 */
class CredentialRecordCodecTest {

    private val credential = StoredCredential(
        type = PrimaryCredentialType.Pattern,
        config = KeyDerivationConfig(
            algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
            saltBytes = KeyDerivationConfig.MINIMUM_SALT_BYTES,
            iterations = 600_000,
            keySizeBits = KeyDerivationConfig.SUPPORTED_KEY_SIZE_BITS,
        ),
        salt = ByteArray(KeyDerivationConfig.MINIMUM_SALT_BYTES) { index -> index.toByte() },
        verifier = ByteArray(CredentialRecordCodec.VERIFIER_BYTES) { index -> (index + 32).toByte() },
    )

    @Test
    fun `round trips every field`() {
        val decoded = CredentialRecordCodec.decode(CredentialRecordCodec.encode(credential))

        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(credential.type, decoded.type)
        assertEquals(credential.config.algorithm, decoded.config.algorithm)
        assertEquals(credential.config.iterations, decoded.config.iterations)
        assertEquals(credential.config.version, decoded.config.version)
        assertEquals(credential.config.saltBytes, decoded.config.saltBytes)
        assertArrayEquals(credential.salt, decoded.salt)
        assertArrayEquals(credential.verifier, decoded.verifier)
    }

    @Test
    fun `the encoded record is exactly as long as its fields say`() {
        val encoded = CredentialRecordCodec.encode(credential)

        assertEquals(
            CredentialRecordCodec.MINIMUM_BYTES,
            encoded.size,
        )
    }

    @Test
    fun `rejects data that is not a record`() {
        assertNull(CredentialRecordCodec.decode(ByteArray(0)))
        assertNull(CredentialRecordCodec.decode("not a credential record at all".toByteArray()))
    }

    @Test
    fun `rejects a truncated record`() {
        val encoded = CredentialRecordCodec.encode(credential)

        assertNull(CredentialRecordCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(CredentialRecordCodec.decode(encoded.copyOf(CredentialRecordCodec.MINIMUM_BYTES - 1)))
    }

    @Test
    fun `rejects an unknown format version`() {
        val encoded = CredentialRecordCodec.encode(credential)
        encoded[4] = 99

        assertNull(CredentialRecordCodec.decode(encoded))
    }

    @Test
    fun `rejects an unknown credential type`() {
        val encoded = CredentialRecordCodec.encode(credential)
        encoded[5] = 42

        assertNull(CredentialRecordCodec.decode(encoded))
    }

    @Test
    fun `rejects an unknown key derivation algorithm`() {
        val encoded = CredentialRecordCodec.encode(credential)
        encoded[6] = 7

        assertNull(CredentialRecordCodec.decode(encoded))
    }

    @Test
    fun `rejects a parameter set below the policy minimum`() {
        val encoded = CredentialRecordCodec.encode(credential)
        writeInt(encoded, 8, KeyDerivationAlgorithm.Pbkdf2HmacSha256.minimumIterations - 1)
        writeInt(encoded, encoded.size - 4, checksum(encoded, 0, encoded.size - 4))

        assertNull(CredentialRecordCodec.decode(encoded))
    }

    @Test
    fun `rejects a salt that is shorter than the policy allows`() {
        val encoded = CredentialRecordCodec.encode(credential)
        encoded[12] = (KeyDerivationConfig.MINIMUM_SALT_BYTES - 1).toByte()
        writeInt(encoded, encoded.size - 4, checksum(encoded, 0, encoded.size - 4))

        assertNull(CredentialRecordCodec.decode(encoded))
    }

    @Test
    fun `rejects a corrupted field`() {
        val encoded = CredentialRecordCodec.encode(credential)
        // Flip a bit in the verifier without touching the checksum.
        encoded[20] = (encoded[20].toInt() xor 0x01).toByte()

        assertNull(CredentialRecordCodec.decode(encoded))
    }

    @Test
    fun `rejects a record whose length disagrees with its salt length`() {
        val encoded = CredentialRecordCodec.encode(credential)
        encoded[12] = 32

        assertNull(CredentialRecordCodec.decode(encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to encode a verifier of the wrong size`() {
        CredentialRecordCodec.encode(
            StoredCredential(
                type = PrimaryCredentialType.Pin,
                config = credential.config,
                salt = credential.salt,
                verifier = ByteArray(CredentialRecordCodec.VERIFIER_BYTES - 1),
            ),
        )
    }
}
