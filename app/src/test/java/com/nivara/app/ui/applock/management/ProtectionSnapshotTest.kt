package com.nivara.app.ui.applock.management

import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.applock.ApplicationProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the one judgement the management list makes: what Nivara may claim about an
 * application, given what the device has and what the stored set says.
 *
 * The distinction the suite exists for is the one that is easy to get wrong and expensive to get
 * wrong: an unreadable protected set is not an empty one. Every case below says that a row either
 * makes a claim the stored configuration supports, or makes none at all.
 */
class ProtectionSnapshotTest {

    private val camera = InstalledApplication("com.example.camera", "Camera")
    private val notes = InstalledApplication("com.example.notes", "Notes")

    @Test
    fun `an application in the stored set is protected when the capabilities are in place`() {
        val snapshot = readableSnapshot("com.example.camera", requirementsSatisfied = true)

        assertEquals(ApplicationProtectionState.Protected, snapshot.stateOf(camera))
    }

    @Test
    fun `an application outside the stored set is not protected`() {
        val snapshot = readableSnapshot("com.example.camera", requirementsSatisfied = true)

        assertEquals(ApplicationProtectionState.NotProtected, snapshot.stateOf(notes))
    }

    @Test
    fun `a stored application is protected but unavailable when a capability is missing`() {
        val snapshot = readableSnapshot("com.example.camera", requirementsSatisfied = false)

        assertEquals(ApplicationProtectionState.ProtectedButUnavailable, snapshot.stateOf(camera))
    }

    @Test
    fun `a missing capability does not make an unprotected application look protected`() {
        val snapshot = readableSnapshot("com.example.camera", requirementsSatisfied = false)

        assertEquals(ApplicationProtectionState.NotProtected, snapshot.stateOf(notes))
    }

    @Test
    fun `an empty stored set means nothing is protected, not that nothing may be claimed`() {
        val snapshot = ProtectionSnapshot(
            protected = ProtectedSetRead.Readable(emptySet()),
            requirementsSatisfied = true,
        )

        assertFalse(snapshot.storedSetUnreadable)
        assertEquals(ApplicationProtectionState.NotProtected, snapshot.stateOf(camera))
    }

    @Test
    fun `an unreadable stored set claims nothing about any application`() {
        val snapshot = ProtectionSnapshot(
            protected = ProtectedSetRead.Unreadable,
            requirementsSatisfied = true,
        )

        assertTrue(snapshot.storedSetUnreadable)
        assertNull(
            "an unreadable configuration is not an empty one",
            snapshot.stateOf(camera),
        )
        assertNull(snapshot.stateOf(notes))
    }

    @Test
    fun `a stored name that is not installed says nothing about the applications that are`() {
        val snapshot = readableSnapshot("com.example.gone", requirementsSatisfied = true)

        assertEquals(ApplicationProtectionState.NotProtected, snapshot.stateOf(camera))
    }

    @Test
    fun `identity is the package name, never the label`() {
        val snapshot = readableSnapshot("com.example.camera", requirementsSatisfied = true)
        val sameLabelDifferentPackage = InstalledApplication("com.example.other", "Camera")

        assertEquals(ApplicationProtectionState.Protected, snapshot.stateOf(camera))
        assertEquals(ApplicationProtectionState.NotProtected, snapshot.stateOf(sameLabelDifferentPackage))
    }

    private fun readableSnapshot(
        vararg packageNames: String,
        requirementsSatisfied: Boolean,
    ): ProtectionSnapshot = ProtectionSnapshot(
        protected = ProtectedSetRead.Readable(packageNames.toSet()),
        requirementsSatisfied = requirementsSatisfied,
    )
}
