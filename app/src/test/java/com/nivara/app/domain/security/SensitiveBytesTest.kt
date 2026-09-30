package com.nivara.app.domain.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for secret-buffer handling.
 *
 * Erasure on a managed runtime is best effort (see the type's documentation); these tests only
 * assert what the type actually promises — that the live buffer is overwritten, that copies are
 * explicit, and that nothing about the type reveals content.
 */
class SensitiveBytesTest {

    @Test
    fun `of copies the source so later mutation of the source is not visible`() {
        val source = byteArrayOf(1, 2, 3, 4)

        val secret = SensitiveBytes.of(source)
        source[0] = 99

        assertEquals(1, secret.unsafeByteArray()[0])
    }

    @Test
    fun `wrap takes ownership without copying`() {
        val owned = byteArrayOf(1, 2, 3, 4)

        val secret = SensitiveBytes.wrap(owned)
        owned[0] = 99

        assertEquals(99, secret.unsafeByteArray()[0])
    }

    @Test
    fun `clear overwrites the buffer and is idempotent`() {
        val secret = SensitiveBytes.of(byteArrayOf(7, 7, 7, 7))

        secret.clear()
        secret.clear()

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), secret.unsafeByteArray())
        assertTrue(secret.isCleared)
    }

    @Test
    fun `copyBytes returns a detached copy`() {
        val secret = SensitiveBytes.of(byteArrayOf(1, 2, 3, 4))

        val copy = secret.copyBytes()
        copy.fill(0)

        assertFalse(secret.isCleared)
        assertEquals(4, secret.size)
    }

    @Test
    fun `isCleared is false for any non-zero byte`() {
        val secret = SensitiveBytes.of(byteArrayOf(0, 0, 0, 1))

        assertFalse(secret.isCleared)
    }

    @Test
    fun `contentEquals compares values and lengths`() {
        val first = SensitiveBytes.of(byteArrayOf(1, 2, 3, 4))
        val same = SensitiveBytes.of(byteArrayOf(1, 2, 3, 4))
        val different = SensitiveBytes.of(byteArrayOf(1, 2, 3, 5))
        val shorter = SensitiveBytes.of(byteArrayOf(1, 2, 3))

        assertTrue(first.contentEquals(same))
        assertFalse(first.contentEquals(different))
        assertFalse(first.contentEquals(shorter))
    }

    @Test
    fun `equal content is not object equality`() {
        // Comparing secrets must be a deliberate act: the type does not fake equality, so a
        // mistaken `==` cannot silently perform a content comparison.
        val first = SensitiveBytes.of(byteArrayOf(1, 2, 3, 4))
        val second = SensitiveBytes.of(byteArrayOf(1, 2, 3, 4))

        assertNotEquals(first, second)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `a cleared buffer compares equal to another cleared buffer`() {
        val first = SensitiveBytes.of(byteArrayOf(1, 2, 3, 4))
        val second = SensitiveBytes.of(byteArrayOf(5, 6, 7, 8))

        first.clear()
        second.clear()

        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `toString never reveals content`() {
        val secret = SensitiveBytes.of(byteArrayOf(0x41, 0x42, 0x43, 0x44))

        assertEquals("SensitiveBytes(size=4, content=REDACTED)", secret.toString())
        assertFalse(secret.toString().contains("41"))
        assertFalse(secret.toString().contains("ABCD"))
    }

    @Test
    fun `size reports the buffer length`() {
        assertEquals(32, SensitiveBytes.of(ByteArray(32)).size)
        assertEquals(1, SensitiveBytes.wrap(ByteArray(1)).size)
    }
}
