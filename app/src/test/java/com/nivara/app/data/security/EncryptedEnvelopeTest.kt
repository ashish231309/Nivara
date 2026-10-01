package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.testing.hexToBytes
import com.nivara.app.testing.toHex
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for the versioned envelope format, at the byte level.
 *
 * The fixture bytes in this file were produced by an independent implementation of
 * `docs/crypto/envelope-format.md` (Python + OpenSSL, see `tools/crypto_reference.py`). Decrypting
 * them here proves the Kotlin implementation agrees with the specification on the header layout,
 * the authenticated data and the handling of the GCM tag — not merely with itself.
 */
class EncryptedEnvelopeTest {

    private val keyA: SecretKey = SecretKeySpec(KEY_A_HEX.hexToBytes(), "AES")
    private val keyB: SecretKey = SecretKeySpec(KEY_B_HEX.hexToBytes(), "AES")

    // ---------------------------------------------------------------- interop vectors

    @Test
    fun `decrypts an envelope produced by an independent implementation`() {
        val plaintext = EncryptedEnvelope.decrypt(
            envelope = ENVELOPE_A_HEX.hexToBytes(),
            key = keyA,
            expectedContext = EncryptionContext.VaultContent,
        )

        assertEquals(PLAINTEXT_A, plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun `decrypts a recovery-purpose fixture produced by an independent implementation`() {
        val plaintext = EncryptedEnvelope.decrypt(
            envelope = ENVELOPE_B_HEX.hexToBytes(),
            key = keyB,
            expectedContext = EncryptionContext.RecoveryEnvelope,
        )

        assertEquals(PLAINTEXT_B, plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun `the same key and nonce under a different purpose no longer verifies`() {
        // ENVELOPE_OTHER_CONTEXT is built from the same key, nonce and plaintext as ENVELOPE_A but
        // declares the ApplicationSecurityData purpose. Its tag must differ, and it must not be
        // accepted for the purpose ENVELOPE_A was written for.
        assertNotEquals(ENVELOPE_A_HEX, ENVELOPE_OTHER_CONTEXT_HEX)

        val accepted = EncryptedEnvelope.decrypt(
            envelope = ENVELOPE_OTHER_CONTEXT_HEX.hexToBytes(),
            key = keyA,
            expectedContext = EncryptionContext.ApplicationSecurityData,
        )
        assertEquals(PLAINTEXT_A, accepted.toString(Charsets.UTF_8))

        assertThrows(CryptographicFailure.ContextMismatch::class.java) {
            EncryptedEnvelope.decrypt(
                envelope = ENVELOPE_OTHER_CONTEXT_HEX.hexToBytes(),
                key = keyA,
                expectedContext = EncryptionContext.VaultContent,
            )
        }
    }

    @Test
    fun `rejects a fixture whose ciphertext was altered`() {
        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            EncryptedEnvelope.decrypt(
                envelope = ENVELOPE_TAMPERED_CIPHERTEXT_HEX.hexToBytes(),
                key = keyA,
                expectedContext = EncryptionContext.VaultContent,
            )
        }
    }

    @Test
    fun `rejects a fixture whose nonce was altered`() {
        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            EncryptedEnvelope.decrypt(
                envelope = ENVELOPE_TAMPERED_NONCE_HEX.hexToBytes(),
                key = keyA,
                expectedContext = EncryptionContext.VaultContent,
            )
        }
    }

    @Test
    fun `rejects a truncated fixture`() {
        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            EncryptedEnvelope.decrypt(
                envelope = ENVELOPE_TRUNCATED_HEX.hexToBytes(),
                key = keyA,
                expectedContext = EncryptionContext.VaultContent,
            )
        }
    }

    @Test
    fun `rejects a fixture with a foreign magic`() {
        assertThrows(CryptographicFailure.UnsupportedEnvelope::class.java) {
            EncryptedEnvelope.decrypt(
                envelope = ENVELOPE_BAD_MAGIC_HEX.hexToBytes(),
                key = keyA,
                expectedContext = EncryptionContext.VaultContent,
            )
        }
    }

    @Test
    fun `rejects a fixture with a set reserved byte`() {
        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            EncryptedEnvelope.decrypt(
                envelope = ENVELOPE_BAD_RESERVED_HEX.hexToBytes(),
                key = keyA,
                expectedContext = EncryptionContext.VaultContent,
            )
        }
    }

    // ---------------------------------------------------------------- produced here

    @Test
    fun `round trip returns the original plaintext`() {
        val plaintext = "nivara-envelope-round-trip".toByteArray()

        val envelope = EncryptedEnvelope.encrypt(
            plaintext = plaintext,
            key = keyA,
            context = EncryptionContext.VaultMetadata,
            nonce = FIXED_NONCE.hexToBytes(),
        )
        val decrypted = EncryptedEnvelope.decrypt(
            envelope = envelope,
            key = keyA,
            expectedContext = EncryptionContext.VaultMetadata,
        )

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `envelope layout matches the specification`() {
        val plaintext = ByteArray(100)

        val envelope = EncryptedEnvelope.encrypt(
            plaintext = plaintext,
            key = keyA,
            context = EncryptionContext.VaultContent,
            nonce = FIXED_NONCE.hexToBytes(),
        )

        assertEquals(EncryptedEnvelope.HEADER_LENGTH, 21)
        assertEquals(EncryptedEnvelope.MINIMUM_LENGTH, 37)
        assertEquals(21 + plaintext.size + EncryptedEnvelope.TAG_LENGTH_BYTES, envelope.size)
        assertEquals("4e495652", envelope.copyOfRange(0, 4).toHex())
        assertEquals(EncryptedEnvelope.VERSION, envelope[4].toInt())
        assertEquals(EncryptedEnvelope.KEY_SCHEME_ANDROID_KEYSTORE, envelope[5].toInt())
        assertEquals(EncryptedEnvelope.ALGORITHM_AES_256_GCM, envelope[6].toInt())
        assertEquals(EncryptionContext.VaultContent.tag, envelope[7].toInt())
        assertEquals(0, envelope[8].toInt())
        assertEquals(FIXED_NONCE, envelope.copyOfRange(9, 21).toHex())
    }

    @Test
    fun `plaintext does not appear in the envelope`() {
        val plaintext = "a-distinctive-plaintext-that-must-not-be-visible".toByteArray()

        val envelope = EncryptedEnvelope.encrypt(
            plaintext = plaintext,
            key = keyA,
            context = EncryptionContext.VaultContent,
            nonce = FIXED_NONCE.hexToBytes(),
        )

        assertFalse(envelope.toHex().contains(plaintext.toHex()))
    }

    @Test
    fun `a different nonce produces a different envelope for the same plaintext`() {
        val plaintext = "same-plaintext".toByteArray()

        val first = EncryptedEnvelope.encrypt(plaintext, keyA, EncryptionContext.VaultContent, "000000000000000000000000".hexToBytes())
        val second = EncryptedEnvelope.encrypt(plaintext, keyA, EncryptionContext.VaultContent, "000000000000000000000001".hexToBytes())

        assertNotEquals(first.toHex(), second.toHex())
        // The ciphertext body differs because the counter block differs; this is the property that
        // makes nonce reuse dangerous, and the reason nonces are never caller-supplied in
        // production code.
        assertNotEquals(
            first.copyOfRange(21, first.size).toHex(),
            second.copyOfRange(21, second.size).toHex(),
        )
    }

    @Test
    fun `decrypting with a different key fails`() {
        val envelope = EncryptedEnvelope.encrypt(
            plaintext = "secret".toByteArray(),
            key = keyA,
            context = EncryptionContext.VaultContent,
            nonce = FIXED_NONCE.hexToBytes(),
        )

        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            EncryptedEnvelope.decrypt(envelope, keyB, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an edited nonce on a locally produced envelope fails`() {
        val envelope = EncryptedEnvelope.encrypt(
            plaintext = "secret".toByteArray(),
            key = keyA,
            context = EncryptionContext.VaultContent,
            nonce = FIXED_NONCE.hexToBytes(),
        )
        envelope[9] = (envelope[9].toInt() xor 0x01).toByte()

        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            EncryptedEnvelope.decrypt(envelope, keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an edited ciphertext on a locally produced envelope fails`() {
        val envelope = EncryptedEnvelope.encrypt(
            plaintext = "secret".toByteArray(),
            key = keyA,
            context = EncryptionContext.VaultContent,
            nonce = FIXED_NONCE.hexToBytes(),
        )
        envelope[envelope.lastIndex] = (envelope[envelope.lastIndex].toInt() xor 0x01).toByte()

        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            EncryptedEnvelope.decrypt(envelope, keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an edited authenticated header byte fails`() {
        val envelope = EncryptedEnvelope.encrypt(
            plaintext = "secret".toByteArray(),
            key = keyA,
            context = EncryptionContext.VaultContent,
            nonce = FIXED_NONCE.hexToBytes(),
        )
        // Move the envelope to a different declared purpose while keeping the tag.
        envelope[7] = EncryptionContext.VaultMetadata.tag.toByte()

        assertThrows(CryptographicFailure.ContextMismatch::class.java) {
            EncryptedEnvelope.decrypt(envelope, keyA, EncryptionContext.VaultContent)
        }
        // Even when the caller asks for the purpose the envelope now claims, the tag does not
        // verify, because the purpose byte is authenticated.
        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            EncryptedEnvelope.decrypt(envelope, keyA, EncryptionContext.VaultMetadata)
        }
    }

    @Test
    fun `an unsupported version is rejected`() {
        assertThrows(CryptographicFailure.UnsupportedVersion::class.java) {
            EncryptedEnvelope.decrypt(envelopeWith(4, 2), keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an unsupported key scheme is rejected`() {
        assertThrows(CryptographicFailure.UnsupportedKeyScheme::class.java) {
            EncryptedEnvelope.decrypt(envelopeWith(5, 2), keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an unsupported algorithm is rejected`() {
        assertThrows(CryptographicFailure.UnsupportedAlgorithm::class.java) {
            EncryptedEnvelope.decrypt(envelopeWith(6, 2), keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an undefined purpose tag is rejected`() {
        assertThrows(CryptographicFailure.UnsupportedContext::class.java) {
            EncryptedEnvelope.decrypt(envelopeWith(7, 0x0A), keyA, EncryptionContext.VaultContent)
        }
        assertThrows(CryptographicFailure.UnsupportedContext::class.java) {
            EncryptedEnvelope.decrypt(envelopeWith(7, 0x00), keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `every defined purpose tag is accepted by the parser`() {
        EncryptionContext.entries.forEach { context ->
            val envelope = EncryptedEnvelope.encrypt(
                plaintext = "purpose".toByteArray(),
                key = keyA,
                context = context,
                nonce = FIXED_NONCE.hexToBytes(),
            )

            assertArrayEquals(
                "purpose".toByteArray(),
                EncryptedEnvelope.decrypt(envelope, keyA, context),
            )
        }
    }

    @Test
    fun `an envelope shorter than the minimum is rejected before decryption`() {
        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            EncryptedEnvelope.decrypt(ByteArray(EncryptedEnvelope.MINIMUM_LENGTH - 1), keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an envelope larger than the maximum is rejected`() {
        val oversized = ByteArray(EncryptedEnvelope.MAXIMUM_LENGTH + 1)
        "NIVR".toByteArray(Charsets.US_ASCII).copyInto(oversized, 0)
        oversized[4] = 1
        oversized[5] = 1
        oversized[6] = 1
        oversized[7] = EncryptionContext.VaultContent.tag.toByte()

        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            EncryptedEnvelope.decrypt(oversized, keyA, EncryptionContext.VaultContent)
        }
    }

    @Test
    fun `an empty plaintext round trips`() {
        val envelope = EncryptedEnvelope.encrypt(ByteArray(0), keyA, EncryptionContext.VaultContent, FIXED_NONCE.hexToBytes())

        assertEquals(EncryptedEnvelope.MINIMUM_LENGTH, envelope.size)
        assertArrayEquals(ByteArray(0), EncryptedEnvelope.decrypt(envelope, keyA, EncryptionContext.VaultContent))
    }

    @Test
    fun `a non-AES key is rejected`() {
        val wrongAlgorithm: SecretKey = SecretKeySpec(ByteArray(32), "HmacSHA256")

        assertThrows(CryptographicFailure.InvalidKey::class.java) {
            EncryptedEnvelope.encrypt(ByteArray(1), wrongAlgorithm, EncryptionContext.VaultContent, FIXED_NONCE.hexToBytes())
        }
    }

    @Test
    fun `a key of the wrong size is rejected`() {
        val shortKey: SecretKey = SecretKeySpec(ByteArray(16), "AES")

        assertThrows(CryptographicFailure.InvalidKey::class.java) {
            EncryptedEnvelope.encrypt(ByteArray(1), shortKey, EncryptionContext.VaultContent, FIXED_NONCE.hexToBytes())
        }
        assertThrows(CryptographicFailure.InvalidKey::class.java) {
            EncryptedEnvelope.decrypt(
                EncryptedEnvelope.encrypt(ByteArray(1), keyA, EncryptionContext.VaultContent, FIXED_NONCE.hexToBytes()),
                shortKey,
                EncryptionContext.VaultContent,
            )
        }
    }

    @Test
    fun `a nonce of the wrong size is rejected when encrypting`() {
        assertThrows(CryptographicFailure.InvalidParameters::class.java) {
            EncryptedEnvelope.encrypt(ByteArray(1), keyA, EncryptionContext.VaultContent, ByteArray(8))
        }
    }

    @Test
    fun `magic detection recognises envelopes and nothing else`() {
        val envelope = EncryptedEnvelope.encrypt(ByteArray(1), keyA, EncryptionContext.VaultContent, FIXED_NONCE.hexToBytes())

        assertEquals(true, EncryptedEnvelope.hasMagic(envelope))
        assertEquals(false, EncryptedEnvelope.hasMagic("NVKW".toByteArray()))
        assertEquals(false, EncryptedEnvelope.hasMagic(ByteArray(0)))
    }

    /** Copies ENVELOPE_A and overwrites one header byte, leaving the tag untouched. */
    private fun envelopeWith(offset: Int, value: Int): ByteArray =
        ENVELOPE_A_HEX.hexToBytes().also { it[offset] = value.toByte() }

    private companion object {
        const val FIXED_NONCE = "000102030405060708090a0b"

        const val KEY_A_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val KEY_B_HEX =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        const val PLAINTEXT_A = "Nivara envelope interop fixture"
        const val PLAINTEXT_B = "recovery-key-material-fixture"

        const val ENVELOPE_A_HEX =
            "4e4956520101010100202122232425262728292a2b9c53d0111ef93a6b740a27a2ae6891d9" +
                "b92798f9f5ef13ce0a9f14663cf83b4484810240d1c78191b554149a2fb899"
        const val ENVELOPE_B_HEX =
            "4e4956520101010300404142434445464748494a4bd209474a010736939f24e1fee465124053" +
                "695a19edfd1e116b056f64b4fca1ad62df4bd8c3cd0687016438a0d5"
        const val ENVELOPE_OTHER_CONTEXT_HEX =
            "4e4956520101010400202122232425262728292a2b9c53d0111ef93a6b740a27a2ae6891d9" +
                "b92798f9f5ef13ce0a9f14663cf83b8f477dc93d1e837aa00f25b1fcfcfaa2"
        const val ENVELOPE_TAMPERED_CIPHERTEXT_HEX =
            "4e4956520101010100202122232425262728292a2b9c53d0111ef93a6b740a27a2ae6891d9" +
                "b92798f9f5ef13ce0a9f14663cf83b4484810240d1c78191b554149a2fb898"
        const val ENVELOPE_TAMPERED_NONCE_HEX =
            "4e4956520101010100212122232425262728292a2b9c53d0111ef93a6b740a27a2ae6891d9" +
                "b92798f9f5ef13ce0a9f14663cf83b4484810240d1c78191b554149a2fb899"
        const val ENVELOPE_TRUNCATED_HEX =
            "4e4956520101010100202122232425262728292a2b9c53d0111ef93a6b74"
        const val ENVELOPE_BAD_MAGIC_HEX =
            "584956520101010100202122232425262728292a2b9c53d0111ef93a6b740a27a2ae6891d9" +
                "b92798f9f5ef13ce0a9f14663cf83b4484810240d1c78191b554149a2fb899"
        const val ENVELOPE_BAD_RESERVED_HEX =
            "4e4956520101010101202122232425262728292a2b9c53d0111ef93a6b740a27a2ae6891d9" +
                "b92798f9f5ef13ce0a9f14663cf83b4484810240d1c78191b554149a2fb899"
    }
}
