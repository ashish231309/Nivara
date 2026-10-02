package com.nivara.app.data.security

import com.nivara.app.domain.security.ContentKeyWrapper
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SensitiveBytes
import com.nivara.app.testing.cryptographicFailure
import com.nivara.app.testing.material
import com.nivara.app.testing.randomBytes
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
 * Tests for content-key wrapping with in-process wrapping keys (credential-derived keys and other
 * content keys).
 *
 * The device-protected path needs the Android Keystore and is covered by
 * `AndroidKeystoreDeviceKeyStoreTest` on a device; this file covers everything that is
 * platform-independent logic.
 */
class NivaraContentKeyWrapperTest {

    private val wrapper: ContentKeyWrapper = NivaraContentKeyWrapper(
        random = SecureRandomGenerator(),
        encryptionService = JcaEncryptionService(random = SecureRandomGenerator()),
    )

    @Test
    fun `wrapping and unwrapping returns the same key material`() = runTest {
        val contentKey = randomKey("vault-key")
        val wrappingKey = randomKey("wrapping-key")

        val wrapped = wrapper.wrap(contentKey, wrappingKey).valueOrFail()
        val unwrapped = wrapper.unwrap(wrapped, wrappingKey).valueOrFail()

        assertArrayEquals(contentKey.material(), unwrapped.material())
        assertEquals(256, unwrapped.keySizeBits)
    }

    @Test
    fun `the wrapped blob has the documented container shape`() = runTest {
        val wrapped = wrapper.wrap(randomKey(), randomKey()).valueOrFail()

        assertEquals(SealedKeyContainer.LENGTH, wrapped.size)
        assertEquals("4e564b57", wrapped.copyOfRange(0, 4).toHex())
        assertEquals(EncryptionContext.KeyWrapping.tag, wrapped[7].toInt())
    }

    @Test
    fun `the content key never appears in the wrapped blob`() = runTest {
        val contentKey = randomKey()
        val contentKeyMaterial = contentKey.material()

        val wrapped = wrapper.wrap(contentKey, randomKey()).valueOrFail()

        assertFalse(wrapped.toHex().contains(contentKeyMaterial.toHex()))
    }

    @Test
    fun `wrapping the same key twice produces different blobs`() = runTest {
        val contentKey = randomKey()
        val wrappingKey = randomKey()

        val first = wrapper.wrap(contentKey, wrappingKey).valueOrFail()
        val second = wrapper.wrap(contentKey, wrappingKey).valueOrFail()

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `unwrapping with a different key fails instead of returning a wrong key`() = runTest {
        val wrapped = wrapper.wrap(randomKey(), randomKey()).valueOrFail()

        val result = wrapper.unwrap(wrapped, randomKey())

        assertEquals(CryptographicFailure.AuthenticationFailed, result.cryptographicFailure())
    }

    @Test
    fun `a tampered blob is rejected`() = runTest {
        val wrappingKey = randomKey()
        val wrapped = wrapper.wrap(randomKey(), wrappingKey).valueOrFail()

        val bodyEdited = wrapped.copyOf().also { it[24] = (it[24].toInt() xor 0x01).toByte() }
        val tagEdited = wrapped.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }
        val nonceEdited = wrapped.copyOf().also { it[8] = (it[8].toInt() xor 0x01).toByte() }

        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            wrapper.unwrap(bodyEdited, wrappingKey).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            wrapper.unwrap(tagEdited, wrappingKey).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            wrapper.unwrap(nonceEdited, wrappingKey).cryptographicFailure(),
        )
    }

    @Test
    fun `a truncated or foreign blob is rejected`() = runTest {
        val wrappingKey = randomKey()
        val wrapped = wrapper.wrap(randomKey(), wrappingKey).valueOrFail()

        assertEquals(
            CryptographicFailure.MalformedEnvelope,
            wrapper.unwrap(wrapped.copyOf(wrapped.size - 1), wrappingKey).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.UnsupportedEnvelope,
            wrapper.unwrap(ByteArray(SealedKeyContainer.LENGTH), wrappingKey).cryptographicFailure(),
        )
    }

    @Test
    fun `a recovery blob is not accepted as a key-wrap blob`() = runTest {
        val recoveryService = HkdfRecoveryKeyEnvelopeService(random = SecureRandomGenerator())
        val wrappingKey = randomKey()
        val recoveryEnvelope = recoveryService
            .sealContentKey(randomKey(), recoveryService.generateRecoveryKey())
            .valueOrFail()

        assertEquals(
            CryptographicFailure.UnsupportedEnvelope,
            wrapper.unwrap(recoveryEnvelope, wrappingKey).cryptographicFailure(),
        )
    }

    @Test
    fun `unwrapping under an unexpected purpose is refused`() = runTest {
        val wrappingKey = randomKey()
        val wrapped = wrapper.wrap(randomKey(), wrappingKey).valueOrFail()

        assertEquals(
            CryptographicFailure.ContextMismatch,
            wrapper.unwrap(wrapped, wrappingKey, EncryptionContext.VaultContent).cryptographicFailure(),
        )
    }

    @Test
    fun `a device-protected content key cannot be wrapped by a key held in memory`() = runTest {
        val deviceProtectedContentKey = EncryptionKey.DeviceProtected(alias = "nivara.test.content")

        val result = wrapper.wrap(deviceProtectedContentKey, randomKey())

        assertEquals(CryptographicFailure.InvalidKey, result.cryptographicFailure())
    }

    @Test
    fun `a cleared wrapping key is refused`() = runTest {
        val wrappingKey = randomKey()
        (wrappingKey as EncryptionKey.InProcess).clear()

        val result = wrapper.wrap(randomKey(), wrappingKey)

        assertEquals(CryptographicFailure.InvalidKey, result.cryptographicFailure())
    }

    @Test
    fun `a wrongly sized content key is refused`() = runTest {
        val shortContentKey = EncryptionKey.fromRawBytes(SensitiveBytes.of(randomBytes(16)), label = "short")

        val result = wrapper.wrap(shortContentKey, randomKey())

        assertEquals(CryptographicFailure.InvalidKey, result.cryptographicFailure())
    }

    @Test
    fun `an unwrapped key can actually decrypt data encrypted with the original key`() = runTest {
        val encryptionService = JcaEncryptionService(random = SecureRandomGenerator())
        val contentKey = randomKey("vault-key")
        val wrappingKey = randomKey("wrapping-key")
        val payload = "vault-metadata".toByteArray()

        val envelope = encryptionService.encrypt(payload, contentKey, EncryptionContext.VaultMetadata).valueOrFail()
        val wrapped = wrapper.wrap(contentKey, wrappingKey).valueOrFail()
        val recovered = wrapper.unwrap(wrapped, wrappingKey).valueOrFail()
        val decrypted = encryptionService.decrypt(envelope, recovered, EncryptionContext.VaultMetadata).valueOrFail()

        assertArrayEquals(payload, decrypted)
        assertTrue(recovered.isDeviceProtected.not())
    }
}
