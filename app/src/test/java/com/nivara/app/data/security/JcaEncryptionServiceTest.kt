package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.testing.cryptographicFailure
import com.nivara.app.testing.keyOf
import com.nivara.app.testing.randomBytes
import com.nivara.app.testing.randomKey
import com.nivara.app.testing.valueOrFail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the authenticated-encryption service that the rest of Nivara uses.
 *
 * Byte-level format cases live in [EncryptedEnvelopeTest]; this file checks the service contract:
 * round trips, freshness of nonces, context binding, tamper rejection and plaintext handling.
 */
class JcaEncryptionServiceTest {

    private val service = JcaEncryptionService(random = SecureRandomGenerator())

    @Test
    fun `round trip returns the original plaintext`() = runTest {
        val key = randomKey()
        val plaintext = "vault-index-entry".toByteArray()

        val envelope = service.encrypt(plaintext, key, EncryptionContext.VaultMetadata).valueOrFail()
        val decrypted = service.decrypt(envelope, key, EncryptionContext.VaultMetadata).valueOrFail()

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `identical plaintext encrypts to different envelopes`() = runTest {
        val key = randomKey()
        val plaintext = "same-value-twice".toByteArray()

        val first = service.encrypt(plaintext, key, EncryptionContext.VaultContent).valueOrFail()
        val second = service.encrypt(plaintext, key, EncryptionContext.VaultContent).valueOrFail()

        assertNotEquals(first.contentToString(), second.contentToString())
        // The nonce is the reason, and it must be different on every call.
        assertNotEquals(
            first.copyOfRange(9, 21).contentToString(),
            second.copyOfRange(9, 21).contentToString(),
        )
    }

    @Test
    fun `a long run of encryptions never repeats a nonce`() = runTest {
        val key = randomKey()

        val nonces = (1..64).map {
            service.encrypt("payload".toByteArray(), key, EncryptionContext.VaultContent)
                .valueOrFail()
                .copyOfRange(9, 21)
                .contentToString()
        }

        assertEquals(nonces.size, nonces.toSet().size)
    }

    @Test
    fun `plaintext is not present in the envelope`() = runTest {
        val plaintext = "recovery-code-ABC123".toByteArray()

        val envelope = service.encrypt(plaintext, randomKey(), EncryptionContext.RecoveryEnvelope).valueOrFail()

        assertFalse(envelope.toString(Charsets.ISO_8859_1).contains(plaintext.toString(Charsets.ISO_8859_1)))
    }

    @Test
    fun `a different key cannot decrypt`() = runTest {
        val envelope = service.encrypt("secret".toByteArray(), randomKey(), EncryptionContext.VaultContent).valueOrFail()

        val result = service.decrypt(envelope, randomKey(), EncryptionContext.VaultContent)

        assertEquals(CryptographicFailure.AuthenticationFailed, result.cryptographicFailure())
    }

    @Test
    fun `an edited ciphertext cannot be decrypted`() = runTest {
        val key = randomKey()
        val envelope = service.encrypt("secret".toByteArray(), key, EncryptionContext.VaultContent).valueOrFail()
        envelope[envelope.lastIndex] = (envelope[envelope.lastIndex].toInt() xor 0x01).toByte()

        val result = service.decrypt(envelope, key, EncryptionContext.VaultContent)

        assertEquals(CryptographicFailure.AuthenticationFailed, result.cryptographicFailure())
    }

    @Test
    fun `an edited nonce cannot be decrypted`() = runTest {
        val key = randomKey()
        val envelope = service.encrypt("secret".toByteArray(), key, EncryptionContext.VaultContent).valueOrFail()
        envelope[9] = (envelope[9].toInt() xor 0x01).toByte()

        val result = service.decrypt(envelope, key, EncryptionContext.VaultContent)

        assertEquals(CryptographicFailure.AuthenticationFailed, result.cryptographicFailure())
    }

    @Test
    fun `an edited authenticated context cannot be decrypted`() = runTest {
        val key = randomKey()
        val envelope = service.encrypt("secret".toByteArray(), key, EncryptionContext.VaultContent).valueOrFail()
        envelope[7] = EncryptionContext.KeyWrapping.tag.toByte()

        assertEquals(
            CryptographicFailure.ContextMismatch,
            service.decrypt(envelope, key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            service.decrypt(envelope, key, EncryptionContext.KeyWrapping).cryptographicFailure(),
        )
    }

    @Test
    fun `an envelope cannot be decrypted under a different purpose`() = runTest {
        val key = randomKey()
        val envelope = service.encrypt(
            plaintext = "only-for-vault-metadata".toByteArray(),
            key = key,
            context = EncryptionContext.VaultMetadata,
        ).valueOrFail()

        EncryptionContext.entries
            .filter { it != EncryptionContext.VaultMetadata }
            .forEach { otherContext ->
                assertEquals(
                    "purpose ${otherContext.name} must not accept a ${EncryptionContext.VaultMetadata.name} envelope",
                    CryptographicFailure.ContextMismatch,
                    service.decrypt(envelope, key, otherContext).cryptographicFailure(),
                )
            }
    }

    @Test
    fun `unsupported envelope metadata is reported as a typed failure`() = runTest {
        val key = randomKey()
        val envelope = service.encrypt("secret".toByteArray(), key, EncryptionContext.VaultContent).valueOrFail()

        assertEquals(
            CryptographicFailure.UnsupportedVersion,
            service.decrypt(envelope.copyOf().also { it[4] = 2 }, key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.UnsupportedKeyScheme,
            service.decrypt(envelope.copyOf().also { it[5] = 2 }, key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.UnsupportedAlgorithm,
            service.decrypt(envelope.copyOf().also { it[6] = 2 }, key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.UnsupportedContext,
            service.decrypt(envelope.copyOf().also { it[7] = 0x7F }, key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.MalformedEnvelope,
            service.decrypt(envelope.copyOf().also { it[8] = 1 }, key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.UnsupportedEnvelope,
            service.decrypt("NOPE".toByteArray() + envelope, key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
    }

    @Test
    fun `malformed input is rejected without touching the cipher`() = runTest {
        val key = randomKey()

        assertEquals(
            CryptographicFailure.MalformedEnvelope,
            service.decrypt(ByteArray(0), key, EncryptionContext.VaultContent).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.MalformedEnvelope,
            service.decrypt(ByteArray(EncryptedEnvelope.MINIMUM_LENGTH - 1), key, EncryptionContext.VaultContent)
                .cryptographicFailure(),
        )
    }

    @Test
    fun `removing any byte from a valid envelope breaks authentication`() = runTest {
        val key = randomKey()
        val envelope = service.encrypt("secret".toByteArray(), key, EncryptionContext.VaultContent).valueOrFail()

        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            service.decrypt(envelope.copyOf(envelope.size - 1), key, EncryptionContext.VaultContent)
                .cryptographicFailure(),
        )
    }

    @Test
    fun `an unusable key is reported instead of producing a weaker result`() = runTest {
        val shortKey = keyOf(randomBytes(16))

        val result = service.encrypt("secret".toByteArray(), shortKey, EncryptionContext.VaultContent)

        assertEquals(CryptographicFailure.InvalidKey, result.cryptographicFailure())
    }

    @Test
    fun `a cleared key cannot be used`() = runTest {
        val key = randomKey()
        (key as EncryptionKey.InProcess).clear()

        val result = service.encrypt("secret".toByteArray(), key, EncryptionContext.VaultContent)

        assertEquals(CryptographicFailure.InvalidKey, result.cryptographicFailure())
    }

    @Test
    fun `clearPlaintextAfterUse overwrites the caller's buffer once the envelope exists`() = runTest {
        val plaintext = "temporary-secret".toByteArray()

        val envelope = service.encrypt(
            plaintext = plaintext,
            key = randomKey(),
            context = EncryptionContext.ApplicationSecurityData,
            clearPlaintextAfterUse = true,
        ).valueOrFail()

        assertTrue(plaintext.all { it == 0.toByte() })
        assertTrue(envelope.size > EncryptedEnvelope.HEADER_LENGTH)
    }

    @Test
    fun `clearPlaintextAfterUse does not touch the buffer when encryption fails`() = runTest {
        val plaintext = "must-survive-a-failure".toByteArray()
        val unusableKey = keyOf(randomBytes(16))

        val result = service.encrypt(plaintext, unusableKey, EncryptionContext.VaultContent, clearPlaintextAfterUse = true)

        assertEquals(CryptographicFailure.InvalidKey, result.cryptographicFailure())
        assertEquals("must-survive-a-failure", plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun `the caller's buffer is untouched when clearing is not requested`() = runTest {
        val plaintext = "keep-me".toByteArray()

        service.encrypt(plaintext, randomKey(), EncryptionContext.VaultContent, clearPlaintextAfterUse = false).valueOrFail()

        assertEquals("keep-me", plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun `an empty payload round trips`() = runTest {
        val key = randomKey()

        val envelope = service.encrypt(ByteArray(0), key, EncryptionContext.VaultMetadata).valueOrFail()
        val decrypted = service.decrypt(envelope, key, EncryptionContext.VaultMetadata).valueOrFail()

        assertArrayEquals(ByteArray(0), decrypted)
    }

    @Test
    fun `a one megabyte payload round trips`() = runTest {
        val key = randomKey()
        val payload = randomBytes(1024 * 1024)

        val envelope = service.encrypt(payload, key, EncryptionContext.VaultContent).valueOrFail()
        val decrypted = service.decrypt(envelope, key, EncryptionContext.VaultContent).valueOrFail()

        assertArrayEquals(payload, decrypted)
        assertEquals(payload.size + EncryptedEnvelope.HEADER_LENGTH + EncryptedEnvelope.TAG_LENGTH_BYTES, envelope.size)
    }
}
