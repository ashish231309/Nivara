package com.nivara.app.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the random source used by every key, nonce and salt in Nivara.
 *
 * These tests check shapes, contracts and the absence of immediately repeated values. They do not
 * attempt to prove randomness statistically — a unit test cannot do that, and a passing suite here
 * says nothing about the quality of the platform's CSPRNG (which is why the implementation uses
 * [java.security.SecureRandom] and never a hand-rolled generator).
 */
class SecureRandomGeneratorTest {

    private val generator = SecureRandomGenerator()

    @Test
    fun `key material has the expected length`() {
        assertEquals(32, SecureRandomGenerator.KEY_SIZE_BYTES)
        assertEquals(32, generator.nextKeyBytes().size)
        assertEquals(48, generator.nextBytes(48).size)
    }

    @Test
    fun `byte arrays have the expected length`() {
        assertEquals(12, generator.nextByteArray(12).size)
        assertEquals(16, generator.nextByteArray(16).size)
        assertEquals(1, generator.nextByteArray(1).size)
    }

    @Test
    fun `repeated generation does not immediately repeat a value`() {
        // A short run of distinct values is all a unit test can check; it catches obvious
        // mistakes (a constant, a timestamp, a counter reset) without pretending to be a
        // statistical test of the generator.
        val values = (1..16).map { generator.nextBytes(32).unsafeByteArray().toList() }

        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `nonce and salt style generation also differs between calls`() {
        val nonces = (1..16).map { generator.nextByteArray(12).toList() }
        val salts = (1..16).map { generator.nextBytes(16).unsafeByteArray().toList() }

        assertEquals(nonces.size, nonces.toSet().size)
        assertEquals(salts.size, salts.toSet().size)
    }

    @Test
    fun `generated keys are not trivially empty`() {
        val key = generator.nextKeyBytes()

        assertFalse(key.isCleared)
    }

    @Test
    fun `non-positive sizes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { generator.nextBytes(0) }
        assertThrows(IllegalArgumentException::class.java) { generator.nextBytes(-1) }
        assertThrows(IllegalArgumentException::class.java) { generator.nextByteArray(0) }
    }

    @Test
    fun `different generator instances produce different values`() {
        assertNotEquals(
            SecureRandomGenerator().nextBytes(32).unsafeByteArray().toList(),
            SecureRandomGenerator().nextBytes(32).unsafeByteArray().toList(),
        )
    }

    @Test
    fun `toString reports the provider without exposing any value`() {
        val description = generator.toString()

        assertTrue(description.startsWith("SecureRandomGenerator("))
        assertTrue(description.contains("algorithm="))
    }
}
