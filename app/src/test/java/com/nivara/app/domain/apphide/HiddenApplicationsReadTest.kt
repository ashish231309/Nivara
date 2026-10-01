package com.nivara.app.domain.apphide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the three outcomes of asking which applications are hidden.
 *
 * This is the fail-closed rule of the whole feature, so it is tested as a rule rather than case by
 * case: an empty stored set and a set that could not be read must never be confusable, because one
 * of them means "nothing is hidden" and the other means "Nivara cannot tell you" — and reading the
 * second as the first would expose applications the user asked to keep out of sight.
 */
class HiddenApplicationsReadTest {

    private val camera = HiddenApplication("com.example.camera")
    private val notes = HiddenApplication("com.example.notes")

    @Test
    fun `a readable set answers for its members and for everything else`() {
        val read = HiddenApplicationsRead.Available(setOf(camera))

        assertEquals(ApplicationVisibility.Hidden, read.visibilityOf("com.example.camera"))
        assertEquals(ApplicationVisibility.Visible, read.visibilityOf("com.example.notes"))
        assertEquals(1, read.hiddenCount)
        assertTrue(read.isAvailable)
    }

    @Test
    fun `a readable empty set means every application is visible`() {
        val read = HiddenApplicationsRead.Available(emptySet())

        assertEquals(
            "an empty hidden set is an answer, not the absence of one",
            ApplicationVisibility.Visible,
            read.visibilityOf("com.example.camera"),
        )
        assertEquals(0, read.hiddenCount)
        assertTrue(read.isAvailable)
    }

    @Test
    fun `an unreadable set claims nothing about any application`() {
        val read = HiddenApplicationsRead.Unreadable

        assertNull("an unreadable set is not an empty one", read.visibilityOf("com.example.camera"))
        assertNull(read.hiddenCount)
        assertFalse(read.isAvailable)
    }

    @Test
    fun `an unavailable set claims nothing either`() {
        val read = HiddenApplicationsRead.Unavailable

        assertNull(read.visibilityOf("com.example.camera"))
        assertNull(read.hiddenCount)
        assertFalse(read.isAvailable)
    }

    @Test
    fun `the two failure cases are different outcomes`() {
        assertFalse(
            "damage to a file and unreachable storage are different facts",
            HiddenApplicationsRead.Unreadable == HiddenApplicationsRead.Unavailable,
        )
    }

    @Test
    fun `visibility follows the package name, never a label`() {
        val read = HiddenApplicationsRead.Available(setOf(camera, notes))

        assertEquals(ApplicationVisibility.Hidden, read.visibilityOf("com.example.notes"))
        assertEquals(
            "a name that was never stored is visible regardless of what it is called",
            ApplicationVisibility.Visible,
            read.visibilityOf("com.example.other"),
        )
    }
}
