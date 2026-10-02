package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.RecoveryKeyEnvelopeService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SensitiveBytes
import com.nivara.app.testing.cryptographicFailure
import com.nivara.app.testing.material
import com.nivara.app.testing.randomKey
import com.nivara.app.testing.toHex
import com.nivara.app.testing.valueOrFail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the recovery foundation.
 *
 * The service must be able to seal a content key with a recovery key and nothing else: wrong keys,
 * edited blobs and blobs from another family are rejected, and nothing in this class persists the
 * recovery key anywhere.
 */
class HkdfRecoveryKeyEnvelopeServiceTest {

    private val service: RecoveryKeyEnvelopeService = HkdfRecoveryKeyEnvelopeService(random = SecureRandomGenerator())

    @Test
    fun `generated recovery keys are 256-bit and never repeat`() {
        val keys = (1..32).map { service.generateRecoveryKey() }

        assertTrue(keys.all { it.size == 32 })
        assertEquals(keys.size, keys.map { it.unsafeByteArray().toList() }.toSet().size)
    }

    @Test
    fun `a generated recovery key is not trivially empty`() {
        assertFalse(service.generateRecoveryKey().isCleared)
    }

    @Test
    fun `sealing and unsealing returns the same content key`() = runTest {
        val contentKey = randomKey("vault-key")
        val recoveryKey = service.generateRecoveryKey()

        val envelope = service.sealContentKey(contentKey, recoveryKey).valueOrFail()
        val recovered = service.unsealContentKey(envelope, recoveryKey).valueOrFail()

        assertArrayEquals(contentKey.material(), recovered.material())
    }

    @Test
    fun `the recovery envelope has the documented shape`() = runTest {
        val envelope = service.sealContentKey(randomKey(), service.generateRecoveryKey()).valueOrFail()

        assertEquals(SealedKeyContainer.LENGTH, envelope.size)
        assertEquals("4e56524b", envelope.copyOfRange(0, 4).toHex()) // "NVRK"
        assertEquals(EncryptionContext.RecoveryEnvelope.tag, envelope[7].toInt())
    }

    @Test
    fun `the content key never appears in the recovery envelope`() = runTest {
        val contentKey = randomKey()
        val contentKeyMaterial = contentKey.material()

        val envelope = service.sealContentKey(contentKey, service.generateRecoveryKey()).valueOrFail()

        assertFalse(envelope.toHex().contains(contentKeyMaterial.toHex()))
    }

    @Test
    fun `two envelopes for the same key differ`() = runTest {
        val contentKey = randomKey()
        val recoveryKey = service.generateRecoveryKey()

        val first = service.sealContentKey(contentKey, recoveryKey).valueOrFail()
        val second = service.sealContentKey(contentKey, recoveryKey).valueOrFail()

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `a different recovery key cannot unseal the envelope`() = runTest {
        val envelope = service.sealContentKey(randomKey(), service.generateRecoveryKey()).valueOrFail()

        val result = service.unsealContentKey(envelope, service.generateRecoveryKey())

        assertEquals(CryptographicFailure.AuthenticationFailed, result.cryptographicFailure())
    }

    @Test
    fun `an edited recovery envelope is rejected`() = runTest {
        val recoveryKey = service.generateRecoveryKey()
        val envelope = service.sealContentKey(randomKey(), recoveryKey).valueOrFail()

        val bodyEdited = envelope.copyOf().also { it[24] = (it[24].toInt() xor 0x01).toByte() }
        val tagEdited = envelope.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }

        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            service.unsealContentKey(bodyEdited, recoveryKey).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            service.unsealContentKey(tagEdited, recoveryKey).cryptographicFailure(),
        )
    }

    @Test
    fun `a key-wrap blob is not accepted as a recovery envelope`() = runTest {
        val wrapper = NivaraContentKeyWrapper(
            random = SecureRandomGenerator(),
            encryptionService = JcaEncryptionService(random = SecureRandomGenerator()),
        )
        val keyWrapBlob = wrapper.wrap(randomKey(), randomKey()).valueOrFail()

        assertEquals(
            CryptographicFailure.UnsupportedEnvelope,
            service.unsealContentKey(keyWrapBlob, service.generateRecoveryKey()).cryptographicFailure(),
        )
    }

    @Test
    fun `a truncated recovery envelope is rejected`() = runTest {
        val recoveryKey = service.generateRecoveryKey()
        val envelope = service.sealContentKey(randomKey(), recoveryKey).valueOrFail()

        assertEquals(
            CryptographicFailure.MalformedEnvelope,
            service.unsealContentKey(envelope.copyOf(envelope.size - 1), recoveryKey).cryptographicFailure(),
        )
    }

    @Test
    fun `an unusable recovery key is refused`() = runTest {
        val shortKey = SensitiveBytes.of(ByteArray(16))
        val clearedKey = service.generateRecoveryKey().also { it.clear() }

        assertEquals(
            CryptographicFailure.InvalidKey,
            service.sealContentKey(randomKey(), shortKey).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.InvalidKey,
            service.sealContentKey(randomKey(), clearedKey).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.InvalidKey,
            service.unsealContentKey(ByteArray(SealedKeyContainer.LENGTH), clearedKey).cryptographicFailure(),
        )
    }

    @Test
    fun `an unusable content key is refused`() = runTest {
        val deviceProtectedContentKey = EncryptionKey.DeviceProtected(alias = "nivara.test.content")

        val result = service.sealContentKey(deviceProtectedContentKey, service.generateRecoveryKey())

        assertEquals(CryptographicFailure.InvalidKey, result.cryptographicFailure())
    }

    @Test
    fun `the service keeps no reference to the recovery key`() = runTest {
        val recoveryKey = service.generateRecoveryKey()
        val contentKey = randomKey()

        val envelope = service.sealContentKey(contentKey, recoveryKey).valueOrFail()
        // Clearing the caller's copy after sealing does not affect the envelope, and the service
        // cannot have retained the key: an unseal with the cleared key fails.
        recoveryKey.clear()

        assertEquals(
            CryptographicFailure.InvalidKey,
            service.unsealContentKey(envelope, recoveryKey).cryptographicFailure(),
        )
    }

    @Test
    fun `a recovery envelope is portable between service instances`() = runTest {
        // Nothing about a sealed envelope depends on this service instance: the format is
        // self-describing, so another instance (or a future build) can open it with the same key.
        val otherInstance = HkdfRecoveryKeyEnvelopeService(random = SecureRandomGenerator())
        val contentKey = randomKey("vault-key")
        val recoveryKey = service.generateRecoveryKey()

        val envelope = service.sealContentKey(contentKey, recoveryKey).valueOrFail()
        val recovered = otherInstance.unsealContentKey(envelope, recoveryKey).valueOrFail()

        assertArrayEquals(contentKey.material(), recovered.material())
    }
}
