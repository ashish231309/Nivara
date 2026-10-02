package com.nivara.app.domain.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for key identity and redaction.
 *
 * The important property here is negative: a key must never describe itself in a way that reveals
 * or hints at its material, because keys end up in stack traces, crash reports and debug output.
 */
class EncryptionKeyTest {

    private val material = ByteArray(32) { index -> (index + 1).toByte() }

    @Test
    fun `an in-process key reports its size and protection level`() {
        val key = EncryptionKey.fromRawBytes(SensitiveBytes.of(material), label = "vault-key")

        assertEquals(256, key.keySizeBits)
        assertFalse(key.isDeviceProtected)
        assertEquals("vault-key", key.label)
    }

    @Test
    fun `in-process keys do not expose material through toString`() {
        val key = EncryptionKey.fromRawBytes(SensitiveBytes.of(material), label = "vault-key")

        val description = key.toString()

        assertTrue(description.contains("REDACTED"))
        assertFalse(description.contains("01"))
        assertFalse(description.contains("0102"))
    }

    @Test
    fun `a device-protected key reports its alias and protection level`() {
        val key = EncryptionKey.DeviceProtected(alias = "nivara.key.vault")

        assertEquals(256, key.keySizeBits)
        assertTrue(key.isDeviceProtected)
        assertEquals("keystore:nivara.key.vault", key.label)
    }

    @Test
    fun `device-protected keys describe themselves by alias only`() {
        val key = EncryptionKey.DeviceProtected(alias = "nivara.key.vault")

        assertEquals("EncryptionKey.DeviceProtected(alias=nivara.key.vault, keySizeBits=256)", key.toString())
    }

    @Test
    fun `clearing an in-process key overwrites its material and keeps the key usable-looking`() {
        val key = EncryptionKey.fromRawBytes(SensitiveBytes.of(material), label = "vault-key") as EncryptionKey.InProcess

        key.clear()

        assertArrayEquals(ByteArray(32), key.material.unsafeByteArray())
        assertTrue(key.material.isCleared)
        assertEquals(256, key.keySizeBits)
    }

    @Test
    fun `separate keys built from copies of the same bytes are independent`() {
        val first = EncryptionKey.fromRawBytes(SensitiveBytes.of(material), label = "first")
        val second = EncryptionKey.fromRawBytes(SensitiveBytes.of(material), label = "second")

        (first as EncryptionKey.InProcess).clear()

        assertEquals(256, second.keySizeBits)
        assertFalse((second as EncryptionKey.InProcess).material.isCleared)
    }
}
