package com.nivara.app.domain.applock

import com.nivara.app.domain.app.PackageNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the protected-application model.
 *
 * The model is a package name and nothing else, so what is verified here is the identity rule and
 * the validation that keeps anything else out of the set: a name a person would recognise, a
 * label, an index or an icon can never become a protected application.
 */
class ProtectedApplicationTest {

    @Test
    fun `a package name is required`() {
        assertThrows(IllegalArgumentException::class.java) { ProtectedApplication("") }
        assertThrows(IllegalArgumentException::class.java) { ProtectedApplication("   ") }
    }

    @Test
    fun `a name that could not be a package name is rejected`() {
        val rejected = listOf(
            "com.example camera",
            "com/example/camera",
            "com.example.camera\n",
            "$",
            "a".repeat(PackageNames.MAXIMUM_LENGTH + 1),
            ".",
        )

        rejected.forEach { name ->
            assertThrows("expected '$name' to be rejected", IllegalArgumentException::class.java) {
                ProtectedApplication(name)
            }
        }
    }

    @Test
    fun `ordinary package names are accepted`() {
        val accepted = listOf(
            "com.example.camera",
            "com.example_camera",
            "android",
            "com.example.Notes",
            "com.example.camera2",
            "a".repeat(PackageNames.MAXIMUM_LENGTH),
        )

        accepted.forEach { name ->
            assertEquals(name, ProtectedApplication(name).packageName)
        }
    }

    @Test
    fun `the package name is the identity`() {
        val first = ProtectedApplication("com.example.camera")

        assertEquals(first, ProtectedApplication("com.example.camera"))
        assertEquals(first.hashCode(), ProtectedApplication("com.example.camera").hashCode())
        assertNotEquals(first, ProtectedApplication("com.example.camera2"))
        assertNotEquals("an application is not its package name", first, "com.example.camera")
    }

    @Test
    fun `a set keeps one entry per package name`() {
        val applications = setOf(
            ProtectedApplication("com.example.camera"),
            ProtectedApplication("com.example.camera"),
            ProtectedApplication("com.example.notes"),
        )

        assertEquals(2, applications.size)
    }

    @Test
    fun `the set answers whether it protects a package name`() {
        val applications = setOf(ProtectedApplication("com.example.camera"))

        assertTrue(applications.protects("com.example.camera"))
        assertFalse(applications.protects("com.example.notes"))
        assertFalse(emptySet<ProtectedApplication>().protects("com.example.camera"))
    }

    @Test
    fun `protection is matched by name and never by label`() {
        val applications = listOf(ProtectedApplication("com.example.camera"))

        assertFalse(applications.protects("Camera"))
        assertFalse(applications.protects("com.example.Camera"))
    }
}
