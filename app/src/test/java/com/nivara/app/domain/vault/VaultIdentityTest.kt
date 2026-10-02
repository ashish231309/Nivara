package com.nivara.app.domain.vault

import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.testing.hexToBytes
import com.nivara.app.testing.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault's non-secret identifier.
 *
 * The identity is small and easy to get wrong in ways that matter later: a value that is not exactly
 * sixteen bytes, a comparison that depends on case, a `toString` that leaks the value into a log. All
 * three are checked here rather than trusted.
 */
class VaultIdentityTest {

    private val random = SecureRandomGenerator()

    @Test
    fun `an identity is sixteen random bytes as lower-case hex`() {
        val identity = VaultIdentity.create(random)

        assertEquals(VaultIdentity.BYTES * 2, identity.value.length)
        assertEquals("the value must be lower-case hexadecimal", identity.value, identity.value.lowercase())
        assertTrue(identity.value.all { character -> character in "0123456789abcdef" })
    }

    @Test
    fun `two identities are different`() {
        val first = VaultIdentity.create(random)
        val second = VaultIdentity.create(random)

        assertNotEquals("identities must not collide", first, second)
    }

    @Test
    fun `bytes round-trip through an identity`() {
        val bytes = random.nextByteArray(VaultIdentity.BYTES)

        val identity = VaultIdentity.fromBytes(bytes)

        assertEquals(bytes.toHex(), identity?.value)
        assertEquals(identity, VaultIdentity(identity!!.value))
    }

    @Test
    fun `bytes of the wrong length are not an identity`() {
        assertNull("nothing shorter is guessed at", VaultIdentity.fromBytes(ByteArray(VaultIdentity.BYTES - 1)))
        assertNull(VaultIdentity.fromBytes(ByteArray(VaultIdentity.BYTES + 1)))
        assertNull(VaultIdentity.fromBytes(ByteArray(0)))
    }

    @Test
    fun `a value that is not exactly a hexadecimal identity is rejected`() {
        val valid = VaultIdentity.create(random).value

        assertTrue(VaultIdentity.isWellFormed(valid))
        assertFalse("too short", VaultIdentity.isWellFormed(valid.dropLast(1)))
        assertFalse("too long", VaultIdentity.isWellFormed(valid + "0"))
        assertFalse("upper case", VaultIdentity.isWellFormed(valid.uppercase()))
        assertFalse("not hexadecimal", VaultIdentity.isWellFormed("z" + valid.drop(1)))
        assertFalse("empty", VaultIdentity.isWellFormed(""))
    }

    @Test
    fun `the identity never appears in its own string form`() {
        val identity = VaultIdentity.create(random)

        val text = identity.toString()

        assertFalse("an identifier that appears in output can be correlated", text.contains(identity.value))
        assertTrue(text.contains("REDACTED"))
    }

    @Test
    fun `stored bytes that are a valid identity are recognised byte for byte`() {
        val fixture = "000102030405060708090a0b0c0d0e0f".hexToBytes()

        val identity = VaultIdentity.fromBytes(fixture)

        assertEquals("000102030405060708090a0b0c0d0e0f", identity?.value)
    }
}
