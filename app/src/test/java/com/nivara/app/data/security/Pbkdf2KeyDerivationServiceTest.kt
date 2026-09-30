package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.KeyDerivationAlgorithm
import com.nivara.app.domain.security.KeyDerivationConfig
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SensitiveBytes
import com.nivara.app.testing.cryptographicFailure
import com.nivara.app.testing.hexToBytes
import com.nivara.app.testing.material
import com.nivara.app.testing.toHex
import com.nivara.app.testing.valueOrFail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for credential-based key derivation.
 *
 * The known-answer vectors were produced by an independent PBKDF2-HMAC-SHA-256 implementation
 * (Python/OpenSSL, `tools/crypto_reference.py`), and that implementation was itself validated
 * against the published PBKDF2-HMAC-SHA256 test vector before the vectors below were generated.
 * A test that only compared the KDF against itself would not catch a wrong algorithm or a wrong
 * parameter wiring.
 */
class Pbkdf2KeyDerivationServiceTest {

    private val service = Pbkdf2KeyDerivationService(random = SecureRandomGenerator())

    // ---------------------------------------------------------------- known answers

    @Test
    fun `derives the expected key for the reference vector at 100000 iterations`() = runTest {
        val key = service.deriveKey(
            password = "correct horse battery staple".toCharArray(),
            salt = SensitiveBytes.of(SALT_HEX.hexToBytes()),
            config = configWith(iterations = 100_000),
        ).valueOrFail()

        assertEquals(EXPECTED_AT_100_000, key.material().toHex())
    }

    @Test
    fun `derives the expected key for the reference vector at 200000 iterations`() = runTest {
        val key = service.deriveKey(
            password = "correct horse battery staple".toCharArray(),
            salt = SensitiveBytes.of(SALT_HEX.hexToBytes()),
            config = configWith(iterations = 200_000),
        ).valueOrFail()

        assertEquals(EXPECTED_AT_200_000, key.material().toHex())
    }

    @Test
    fun `derives the expected key for a numeric PIN-style credential`() = runTest {
        val key = service.deriveKey(
            password = "482916".toCharArray(),
            salt = SensitiveBytes.of(SALT_HEX.hexToBytes()),
            config = configWith(iterations = 100_000),
        ).valueOrFail()

        assertEquals(EXPECTED_PIN_AT_100_000, key.material().toHex())
    }

    // ---------------------------------------------------------------- determinism

    @Test
    fun `the same credential, salt and parameters produce the same key`() = runTest {
        val salt = SensitiveBytes.of(SALT_HEX.hexToBytes())

        val first = service.deriveKey("1234".toCharArray(), salt, configWith(100_000)).valueOrFail()
        val second = service.deriveKey("1234".toCharArray(), salt, configWith(100_000)).valueOrFail()

        assertArrayEquals(first.material(), second.material())
    }

    @Test
    fun `a different salt produces a different key`() = runTest {
        val first = service.deriveKey(
            "1234".toCharArray(),
            SensitiveBytes.of(SALT_HEX.hexToBytes()),
            configWith(100_000),
        ).valueOrFail()
        val second = service.deriveKey(
            "1234".toCharArray(),
            SensitiveBytes.of("00112233445566778899aabbccddeeff".hexToBytes()),
            configWith(100_000),
        ).valueOrFail()

        assertNotEquals(first.material().toHex(), second.material().toHex())
    }

    @Test
    fun `a different credential produces a different key`() = runTest {
        val salt = SensitiveBytes.of(SALT_HEX.hexToBytes())

        val first = service.deriveKey("1234".toCharArray(), salt, configWith(100_000)).valueOrFail()
        val second = service.deriveKey("1235".toCharArray(), salt, configWith(100_000)).valueOrFail()

        assertNotEquals(first.material().toHex(), second.material().toHex())
    }

    @Test
    fun `a different iteration count produces a different key`() = runTest {
        val salt = SensitiveBytes.of(SALT_HEX.hexToBytes())

        val first = service.deriveKey("1234".toCharArray(), salt, configWith(100_000)).valueOrFail()
        val second = service.deriveKey("1234".toCharArray(), salt, configWith(150_000)).valueOrFail()

        assertNotEquals(first.material().toHex(), second.material().toHex())
    }

    @Test
    fun `the derived key is a usable 256-bit key`() = runTest {
        val key = service.deriveKey("1234".toCharArray(), service.newSalt(), configWith(100_000)).valueOrFail()

        assertEquals(256, key.keySizeBits)
        assertEquals(false, key.isDeviceProtected)

        val encryptionService = JcaEncryptionService(random = SecureRandomGenerator())
        val envelope = encryptionService.encrypt("vault-key-material".toByteArray(), key, EncryptionContext.KeyWrapping).valueOrFail()
        val decrypted = encryptionService.decrypt(envelope, key, EncryptionContext.KeyWrapping).valueOrFail()

        assertEquals("vault-key-material", decrypted.toString(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- parameters

    @Test
    fun `default parameters follow the documented policy`() {
        val defaults = service.config

        assertEquals(KeyDerivationAlgorithm.Pbkdf2HmacSha256, defaults.algorithm)
        assertEquals(600_000, defaults.iterations)
        assertEquals(256, defaults.keySizeBits)
        assertEquals(16, defaults.saltBytes)
        assertEquals(1, defaults.version)
        assertEquals(100_000, KeyDerivationAlgorithm.Pbkdf2HmacSha256.minimumIterations)
    }

    @Test
    fun `new salts are unique and correctly sized`() {
        val salts = (1..32).map { service.newSalt() }

        assertTrue(salts.all { it.size == service.config.saltBytes })
        assertEquals(salts.size, salts.map { it.unsafeByteArray().toList() }.toSet().size)
    }

    @Test
    fun `an iteration count below the algorithm minimum is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            configWith(iterations = 99_999)
        }
    }

    @Test
    fun `a short salt is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivationConfig(
                algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
                saltBytes = 8,
                iterations = 100_000,
                keySizeBits = 256,
            )
        }
    }

    @Test
    fun `an unsupported derived key size is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivationConfig(
                algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
                saltBytes = 16,
                iterations = 100_000,
                keySizeBits = 128,
            )
        }
    }

    @Test
    fun `an empty credential is rejected at derivation time`() = runTest {
        val result = service.deriveKey(CharArray(0), service.newSalt(), configWith(100_000))

        assertEquals(CryptographicFailure.InvalidParameters, result.cryptographicFailure())
    }

    @Test
    fun `a salt that is too short is rejected at derivation time`() = runTest {
        val result = service.deriveKey(
            password = "1234".toCharArray(),
            salt = SensitiveBytes.of(ByteArray(8) { 1 }),
            config = configWith(100_000),
        )

        assertEquals(CryptographicFailure.InvalidParameters, result.cryptographicFailure())
    }

    @Test
    fun `a cleared salt is rejected at derivation time`() = runTest {
        val salt = service.newSalt()
        salt.clear()

        val result = service.deriveKey("1234".toCharArray(), salt, configWith(100_000))

        assertEquals(CryptographicFailure.InvalidParameters, result.cryptographicFailure())
    }

    @Test
    fun `a derived key can be cleared and stops being usable`() = runTest {
        val key = service.deriveKey("1234".toCharArray(), service.newSalt(), configWith(100_000)).valueOrFail()

        (key as EncryptionKey.InProcess).clear()

        assertTrue(key.material().all { it == 0.toByte() })
    }

    @Test
    fun `the service does not retain the credential parameter buffer`() = runTest {
        val password = "9876".toCharArray()
        val salt = service.newSalt()

        val first = service.deriveKey(password, salt, configWith(100_000)).valueOrFail()
        // The caller may clear its own copy; the derived key must be independent of it.
        password.fill('\u0000')
        val second = service.deriveKey("9876".toCharArray(), salt, configWith(100_000)).valueOrFail()

        assertArrayEquals(first.material(), second.material())
    }

    private fun configWith(iterations: Int): KeyDerivationConfig = KeyDerivationConfig(
        algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
        saltBytes = 16,
        iterations = iterations,
        keySizeBits = 256,
    )

    private companion object {
        /** "nivara-kdf-vector-salt" */
        const val SALT_HEX = "6e69766172612d6b64662d766563746f722d73616c74"

        const val EXPECTED_AT_100_000 =
            "c98121f200bfa7580c8def97aa8fdbff115be66ba09420b8c9fd74894268b25f"
        const val EXPECTED_AT_200_000 =
            "ddec58867f636e55d76c43e656bf5cd851c2707a7906b0bf022c534edb5d8100"
        const val EXPECTED_PIN_AT_100_000 =
            "c81e9e5b842194733099f97e53b5058bd86e69d4fbc682dcdcd76f65acfdad2d"
    }
}
