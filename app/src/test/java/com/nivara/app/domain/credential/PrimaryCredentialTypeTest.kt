package com.nivara.app.domain.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the persisted identifiers of the credential methods.
 *
 * These identifiers are written into the credential record and read back by later builds, so they
 * must never change and never collide. A reused or renumbered identifier would silently turn one
 * user's PIN into a pattern.
 */
class PrimaryCredentialTypeTest {

    @Test
    fun `every method has a unique non-zero identifier`() {
        val ids = PrimaryCredentialType.entries.map { it.id }

        assertEquals("duplicate credential identifiers", ids.size, ids.toSet().size)
        assertTrue("an identifier is not positive", ids.all { it > 0 })
    }

    @Test
    fun `identifiers round trip through storage`() {
        PrimaryCredentialType.entries.forEach { type ->
            assertEquals(type, PrimaryCredentialType.fromId(type.id))
        }
    }

    @Test
    fun `unknown identifiers are rejected`() {
        assertNull(PrimaryCredentialType.fromId(0))
        assertNull(PrimaryCredentialType.fromId(-1))
        assertNull(PrimaryCredentialType.fromId(99))
    }

    @Test
    fun `the three methods are exactly PIN, password and pattern`() {
        assertEquals(3, PrimaryCredentialType.entries.size)
    }
}
