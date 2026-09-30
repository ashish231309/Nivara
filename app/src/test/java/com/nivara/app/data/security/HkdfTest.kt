package com.nivara.app.data.security

import com.nivara.app.testing.hexToBytes
import com.nivara.app.testing.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Known-answer tests for HKDF, taken from RFC 5869 appendix A.
 *
 * These vectors come from the specification itself, so they verify that Nivara's implementation
 * is a correct HKDF-SHA-256 rather than merely self-consistent.
 */
class HkdfTest {

    @Test
    fun `test case A_1 produces the published output`() {
        val okm = Hkdf.deriveKey(
            ikm = "0b".repeat(22).hexToBytes(),
            salt = "000102030405060708090a0b0c".hexToBytes(),
            info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
            length = 42,
        )

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun `test case A_2 produces the published output for longer inputs`() {
        val okm = Hkdf.deriveKey(
            ikm = (
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                    "404142434445464748494a4b4c4d4e4f"
                ).hexToBytes(),
            salt = (
                "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
                    "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                    "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
                ).hexToBytes(),
            info = (
                "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                    "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                    "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
                ).hexToBytes(),
            length = 82,
        )

        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.toHex(),
        )
    }

    @Test
    fun `test case A_3 produces the published output with empty salt and info`() {
        val okm = Hkdf.deriveKey(
            ikm = "0b".repeat(22).hexToBytes(),
            salt = ByteArray(0),
            info = ByteArray(0),
            length = 42,
        )

        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHex(),
        )
    }

    @Test
    fun `different info labels derive unrelated keys`() {
        val ikm = "0b".repeat(32).hexToBytes()
        val salt = ByteArray(16) { it.toByte() }

        val first = Hkdf.deriveKey(ikm, salt, "nivara.hkdf.keywrap.v1.xor".toByteArray(), 32)
        val second = Hkdf.deriveKey(ikm, salt, "nivara.hkdf.keywrap.v1.mac".toByteArray(), 32)

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `different salts derive unrelated keys`() {
        val ikm = "0b".repeat(32).hexToBytes()
        val info = "nivara.hkdf.keywrap.v1.xor".toByteArray()

        val first = Hkdf.deriveKey(ikm, ByteArray(16) { 1 }, info, 32)
        val second = Hkdf.deriveKey(ikm, ByteArray(16) { 2 }, info, 32)

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `output length is honoured`() {
        val ikm = "0b".repeat(32).hexToBytes()

        assertEquals(16, Hkdf.deriveKey(ikm, ByteArray(16), ByteArray(0), 16).size)
        assertEquals(32, Hkdf.deriveKey(ikm, ByteArray(16), ByteArray(0), 32).size)
        assertEquals(64, Hkdf.deriveKey(ikm, ByteArray(16), ByteArray(0), 64).size)
    }

    @Test
    fun `output length beyond the maximum is rejected`() {
        val ikm = "0b".repeat(32).hexToBytes()

        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.deriveKey(ikm, ByteArray(16), ByteArray(0), 255 * 32 + 1)
        }
    }

    @Test
    fun `hmac is deterministic for the same key and message`() {
        val key = "00".repeat(32).hexToBytes()

        assertEquals(
            Hkdf.hmac(key, "nivara".toByteArray()).toHex(),
            Hkdf.hmac(key, "nivara".toByteArray()).toHex(),
        )
    }
}
