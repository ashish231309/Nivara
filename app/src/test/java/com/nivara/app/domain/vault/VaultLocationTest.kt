package com.nivara.app.domain.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the one value a screen hands the domain.
 *
 * The reference is the only thing in this stage that arrives from outside the domain, and the rule is
 * that a reference Nivara cannot use is refused as a *value* rather than as an exception thrown from
 * inside a screen. These tests check the three ways it can be unusable and the one way it is: a
 * reference the platform produced.
 */
class VaultLocationTest {

    private val reference =
        "content://com.android.externalstorage.documents/tree/primary%3ANivara"

    @Test
    fun `a reference the platform produced is a location`() {
        val location = requireNotNull(VaultLocation.create(reference))

        assertEquals(reference, location.reference)
    }

    @Test
    fun `a blank reference is not a location`() {
        assertNull(VaultLocation.create(""))
        assertNull(VaultLocation.create("   "))
    }

    @Test
    fun `a reference longer than a record can hold is not a location`() {
        assertNull(
            VaultLocation.create("c".repeat(VaultLocation.MAXIMUM_REFERENCE_LENGTH + 1)),
        )
    }

    @Test
    fun `a reference of exactly the permitted length is accepted`() {
        val longest = "c".repeat(VaultLocation.MAXIMUM_REFERENCE_LENGTH)

        assertTrue(requireNotNull(VaultLocation.create(longest)).reference.length == longest.length)
    }

    @Test
    fun `a location never prints the folder it names`() {
        val location = requireNotNull(VaultLocation.create(reference))

        assertFalse(
            "a platform reference is not something output should carry",
            location.toString().contains("Nivara"),
        )
    }
}
