package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.testing.randomBytes
import com.nivara.app.testing.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for the sealed-key container: the format that protects one key with another.
 *
 * Tamper detection is the point of this format, so most cases here are about rejection: an altered
 * body, an altered nonce, an altered header, the wrong wrapping key or a container from another
 * family must all fail rather than produce a key that is merely wrong.
 */
class SealedKeyContainerTest {

    private val wrappingKey = randomBytes(32)
    private val contentKey = randomBytes(32)
    private val nonce = randomBytes(SealedKeyContainer.NONCE_LENGTH)

    private fun seal(
        format: SealedKeyFormat = SealedKeyFormat.KeyWrapping,
        wrapping: ByteArray = wrappingKey,
        content: ByteArray = contentKey,
        nonceBytes: ByteArray = nonce,
    ): ByteArray = SealedKeyContainer.seal(format, wrapping, content, nonceBytes)

    @Test
    fun `opening a container returns the original key`() {
        val container = seal()

        assertArrayEquals(contentKey, SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container))
    }

    @Test
    fun `container has the documented fixed length and header`() {
        val container = seal()

        assertEquals(88, SealedKeyContainer.LENGTH)
        assertEquals(SealedKeyContainer.LENGTH, container.size)
        assertEquals("4e564b57", container.copyOfRange(0, 4).toHex()) // "NVKW"
        assertEquals(SealedKeyContainer.VERSION, container[4].toInt())
        assertEquals(SealedKeyContainer.SCHEME_HKDF_SHA256_XOR_HMAC_SHA256, container[5].toInt())
        assertEquals(0, container[6].toInt())
        assertEquals(SealedKeyFormat.KeyWrapping.context.tag, container[7].toInt())
        assertEquals(nonce.toHex(), container.copyOfRange(8, 24).toHex())
    }

    @Test
    fun `the raw content key never appears in the container`() {
        val container = seal()

        assertFalse(container.toHex().contains(contentKey.toHex()))
    }

    @Test
    fun `a fresh nonce produces a different container for the same key`() {
        val first = seal(nonceBytes = randomBytes(SealedKeyContainer.NONCE_LENGTH))
        val second = seal(nonceBytes = randomBytes(SealedKeyContainer.NONCE_LENGTH))

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `a different content key produces a different body`() {
        val first = seal(content = randomBytes(32))
        val second = seal(content = randomBytes(32))

        assertNotEquals(
            first.copyOfRange(24, 56).toHex(),
            second.copyOfRange(24, 56).toHex(),
        )
    }

    @Test
    fun `an altered authentication tag is rejected`() {
        val container = seal()
        container[SealedKeyContainer.LENGTH - 1] = (container[SealedKeyContainer.LENGTH - 1].toInt() xor 0x01).toByte()

        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container)
        }
    }

    @Test
    fun `an altered body is rejected`() {
        val container = seal()
        container[24] = (container[24].toInt() xor 0x01).toByte()

        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container)
        }
    }

    @Test
    fun `an altered nonce is rejected`() {
        val container = seal()
        container[8] = (container[8].toInt() xor 0x01).toByte()

        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container)
        }
    }

    @Test
    fun `an altered header is rejected`() {
        // The reserved byte is authenticated even though it must be zero: editing it is caught by
        // the tag as well as by validation.
        val withEditedReserved = seal()
        withEditedReserved[6] = 1

        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, withEditedReserved)
        }

        val withEditedContext = seal()
        withEditedContext[7] = SealedKeyFormat.Recovery.context.tag.toByte()

        assertThrows(CryptographicFailure.ContextMismatch::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, withEditedContext)
        }
    }

    @Test
    fun `the wrong wrapping key is rejected`() {
        val container = seal()

        assertThrows(CryptographicFailure.AuthenticationFailed::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, randomBytes(32), container)
        }
    }

    @Test
    fun `a container from another family is rejected`() {
        val recoveryContainer = seal(format = SealedKeyFormat.Recovery)

        assertThrows(CryptographicFailure.UnsupportedEnvelope::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, recoveryContainer)
        }
    }

    @Test
    fun `an unsupported version is rejected`() {
        val container = seal()
        container[4] = 2

        assertThrows(CryptographicFailure.UnsupportedVersion::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container)
        }
    }

    @Test
    fun `an unsupported scheme is rejected`() {
        val container = seal()
        container[5] = 2

        assertThrows(CryptographicFailure.UnsupportedKeyScheme::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container)
        }
    }

    @Test
    fun `truncated or oversized containers are rejected`() {
        val container = seal()

        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container.copyOf(container.size - 1))
        }
        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, container + ByteArray(1))
        }
        assertThrows(CryptographicFailure.MalformedEnvelope::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, wrappingKey, ByteArray(0))
        }
    }

    @Test
    fun `unusable component sizes are rejected when sealing`() {
        assertThrows(CryptographicFailure.InvalidParameters::class.java) {
            seal(nonceBytes = ByteArray(12))
        }
        assertThrows(CryptographicFailure.InvalidKey::class.java) {
            seal(content = ByteArray(16))
        }
        assertThrows(CryptographicFailure.InvalidKey::class.java) {
            seal(wrapping = ByteArray(16))
        }
    }

    @Test
    fun `unusable wrapping key sizes are rejected when opening`() {
        val container = seal()

        assertThrows(CryptographicFailure.InvalidKey::class.java) {
            SealedKeyContainer.open(SealedKeyFormat.KeyWrapping, ByteArray(16), container)
        }
    }

    @Test
    fun `families derive unrelated subkeys from the same wrapping key and nonce`() {
        val keyWrapping = seal(format = SealedKeyFormat.KeyWrapping)
        val recovery = seal(format = SealedKeyFormat.Recovery)

        assertNotEquals(
            keyWrapping.copyOfRange(24, 56).toHex(),
            recovery.copyOfRange(24, 56).toHex(),
        )
        assertArrayEquals(
            contentKey,
            SealedKeyContainer.open(SealedKeyFormat.Recovery, wrappingKey, recovery),
        )
    }

    @Test
    fun `magic detection distinguishes the two families`() {
        val keyWrapping = seal(format = SealedKeyFormat.KeyWrapping)
        val recovery = seal(format = SealedKeyFormat.Recovery)

        assertEquals(true, SealedKeyContainer.hasMagic(keyWrapping, SealedKeyFormat.KeyWrapping))
        assertEquals(false, SealedKeyContainer.hasMagic(keyWrapping, SealedKeyFormat.Recovery))
        assertEquals(true, SealedKeyContainer.hasMagic(recovery, SealedKeyFormat.Recovery))
        assertEquals(false, SealedKeyContainer.hasMagic(ByteArray(0), SealedKeyFormat.Recovery))
    }
}
