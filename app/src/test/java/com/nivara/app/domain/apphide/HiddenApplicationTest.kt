package com.nivara.app.domain.apphide

import com.nivara.app.domain.app.PackageNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the hidden-application model.
 *
 * The model is one value and one rule, so what there is to verify is the rule: the package name is
 * the identity, it is compared exactly, and a name that could not identify an application never
 * becomes one. Everything else about a hidden application — its label, its icon, what it does — is
 * deliberately not part of the model, and a test cannot assert about what does not exist.
 */
class HiddenApplicationTest {

    @Test
    fun `the package name is the whole of the identity`() {
        assertEquals(HiddenApplication("com.example.camera"), HiddenApplication("com.example.camera"))
        assertEquals(
            "equal records agree on their hash",
            HiddenApplication("com.example.camera").hashCode(),
            HiddenApplication("com.example.camera").hashCode(),
        )
    }

    @Test
    fun `two different packages are two different applications`() {
        assertNotEquals(HiddenApplication("com.example.camera"), HiddenApplication("com.example.notes"))
    }

    @Test
    fun `package names are compared exactly, including case`() {
        assertNotEquals(HiddenApplication("com.example.Camera"), HiddenApplication("com.example.camera"))
    }

    @Test
    fun `a set of hidden applications de-duplicates by package name`() {
        val set = setOf(
            HiddenApplication("com.example.camera"),
            HiddenApplication("com.example.camera"),
            HiddenApplication("com.example.notes"),
        )

        assertEquals(2, set.size)
    }

    @Test
    fun `an empty package name is refused`() {
        assertThrows(IllegalArgumentException::class.java) { HiddenApplication("") }
    }

    @Test
    fun `a blank or whitespace package name is refused`() {
        assertThrows(IllegalArgumentException::class.java) { HiddenApplication("   ") }
        assertThrows(IllegalArgumentException::class.java) { HiddenApplication("com.example camera") }
    }

    @Test
    fun `a single-segment name is accepted, because the shared rule is deliberately loose`() {
        // PackageNames refuses what could not be a package name at all — blanks, whitespace, path
        // separators, illegal characters, unbounded length — and does not require a dot. The rule is
        // shared with App Lock's protected set, so it is asserted here as it is rather than as it
        // might be: a name that reaches this feature always comes from the platform's own catalogue,
        // and tightening the shared rule is a change to both features at once.
        assertEquals("camera", HiddenApplication("camera").packageName)
    }

    @Test
    fun `a name with characters a package cannot contain is refused`() {
        for (candidate in listOf(
            "com.example.camera-lite",
            "com.example/camera",
            "com.example.cam*era",
            "com.example.camé",
        )) {
            assertThrows(
                "expected '$candidate' to be refused",
                IllegalArgumentException::class.java,
            ) { HiddenApplication(candidate) }
        }
    }

    @Test
    fun `a name longer than the platform allows is refused, and the boundary is accepted`() {
        // Two dotted names, identical but for one character: the shorter one is the longest the
        // platform allows, the longer one is refused. The dot matters, because a name without one
        // is refused for a different reason and would not prove anything about the length rule.
        val tooLong = "a".repeat(PackageNames.MAXIMUM_LENGTH - 1) + ".b"
        assertEquals(PackageNames.MAXIMUM_LENGTH + 1, tooLong.length)
        assertThrows(IllegalArgumentException::class.java) { HiddenApplication(tooLong) }

        val atLimit = "a".repeat(PackageNames.MAXIMUM_LENGTH - 2) + ".b"
        assertEquals(PackageNames.MAXIMUM_LENGTH, atLimit.length)
        assertEquals(PackageNames.MAXIMUM_LENGTH, HiddenApplication(atLimit).packageName.length)
    }

    @Test
    fun `digits and underscores are usable, as the platform allows`() {
        assertEquals(
            "com.example.app_2",
            HiddenApplication("com.example.app_2").packageName,
        )
    }

    @Test
    fun `the set answers whether it covers a name, exactly`() {
        val hidden = setOf(HiddenApplication("com.example.camera"))

        assertTrue(hidden.hides("com.example.camera"))
        assertFalse(hidden.hides("com.example.notes"))
        assertFalse("a difference in case is a different application", hidden.hides("com.example.Camera"))
    }

    @Test
    fun `an empty set hides nothing`() {
        assertFalse(emptySet<HiddenApplication>().hides("com.example.camera"))
    }
}
