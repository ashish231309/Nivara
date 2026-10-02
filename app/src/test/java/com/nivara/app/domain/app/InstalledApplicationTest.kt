package com.nivara.app.domain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Local JVM tests for the application model.
 *
 * The model carries no platform data by construction, so its rules are exactly what a test can
 * prove: a package name is required, a label is never blank, and identity is the package name —
 * never the label, which a user or an application can change.
 */
class InstalledApplicationTest {

    @Test
    fun `a blank package name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstalledApplication(packageName = "", label = "Camera")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstalledApplication(packageName = "   ", label = "Camera")
        }
    }

    @Test
    fun `a blank label is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstalledApplication(packageName = "com.example.camera", label = "")
        }
    }

    @Test
    fun `the package name is the identity, not the label`() {
        val first = InstalledApplication(packageName = "com.example.camera", label = "Camera")
        val relabelled = InstalledApplication(packageName = "com.example.camera", label = "Kamera")

        assertEquals(first, relabelled)
        assertEquals(first.hashCode(), relabelled.hashCode())
    }

    @Test
    fun `different package names are different applications even with the same label`() {
        val first = InstalledApplication(packageName = "com.example.camera", label = "Camera")
        val second = InstalledApplication(packageName = "com.example.camera2", label = "Camera")

        assertNotEquals(first, second)
    }

    @Test
    fun `an application is not equal to another type or to null`() {
        val application = InstalledApplication(packageName = "com.example.camera", label = "Camera")

        assertNotEquals("an application is not its package name", application, "com.example.camera")
        assertNotEquals("an application is not null", application, null)
    }

    @Test
    fun `a set keeps one entry per package name`() {
        val applications = setOf(
            InstalledApplication(packageName = "com.example.camera", label = "Camera"),
            InstalledApplication(packageName = "com.example.camera", label = "Camera (legacy)"),
            InstalledApplication(packageName = "com.example.notes", label = "Notes"),
        )

        assertEquals(2, applications.size)
    }

    @Test
    fun `copying with a fresh label keeps the identity`() {
        val application = InstalledApplication(packageName = "com.example.notes", label = "Notes")

        val relabelled = application.copy(label = "Notes and lists")

        assertEquals(application, relabelled)
        assertEquals("com.example.notes", relabelled.packageName)
        assertEquals("Notes and lists", relabelled.label)
    }
}
