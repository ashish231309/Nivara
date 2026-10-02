package com.nivara.app.domain.camouflage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the identity model.
 *
 * The model is small enough to fit in a table, which is the point: a fixed set of identities is what
 * keeps camouflage free of a store, a format, a migration and a failure mode. What is asserted here
 * is the shape that the manifest and the screens depend on — the identifiers, the default, and the
 * separation between Nivara's own identity and the benign ones.
 */
class CamouflageProfileTest {

    @Test
    fun `the declared identities are exactly the ones the documents list`() {
        assertEquals(
            listOf("Nivara", "Notes", "Calculator", "Weather"),
            CamouflageProfile.entries.map { profile -> profile.name },
        )
    }

    @Test
    fun `nivara's own identity is the default`() {
        assertEquals(CamouflageProfile.Nivara, CamouflageProfile.Default)
        assertFalse(
            "the identity Nivara ships with must not be a camouflage one",
            CamouflageProfile.Default.isCamouflage,
        )
    }

    @Test
    fun `only nivara's own identity is not camouflage`() {
        assertEquals(
            listOf(CamouflageProfile.Nivara),
            CamouflageProfile.entries.filterNot { profile -> profile.isCamouflage },
        )
        assertEquals(
            listOf(CamouflageProfile.Notes, CamouflageProfile.Calculator, CamouflageProfile.Weather),
            CamouflageProfile.camouflageProfiles,
        )
    }

    @Test
    fun `identifiers are unique, lower-case, and never a package name`() {
        val identifiers = CamouflageProfile.entries.map { profile -> profile.id }

        assertEquals("two identities may not share an identifier", identifiers.size, identifiers.toSet().size)
        identifiers.forEach { id ->
            assertTrue(
                "an identifier is used to derive a component name, so it stays a simple word: $id",
                Regex("[a-z][a-z0-9_]*").matches(id),
            )
            assertFalse("an identifier is not a package name: $id", id.contains('.'))
            assertTrue("an identifier carries no spaces: $id", id.trim() == id)
        }
    }

    @Test
    fun `the identifiers are stable`() {
        // The identifier is part of the component name the manifest declares and the implementation
        // resolves. Renaming one is a change to the manifest, to the resources and to the platform
        // state the user already has, so it is spelled out here rather than left implicit.
        assertEquals("nivara", CamouflageProfile.Nivara.id)
        assertEquals("notes", CamouflageProfile.Notes.id)
        assertEquals("calculator", CamouflageProfile.Calculator.id)
        assertEquals("weather", CamouflageProfile.Weather.id)
    }

    @Test
    fun `there are four identities and three of them are camouflage`() {
        assertEquals(4, CamouflageProfile.entries.size)
        assertEquals(3, CamouflageProfile.camouflageProfiles.size)
    }

    @Test
    fun `the camouflage identities are ordinary utility names`() {
        // A benign identity is the whole point of the feature: a name that stood out would defeat it,
        // and a name that claimed to be something Nivara is not would be a lie about the application.
        assertEquals(
            listOf("Notes", "Calculator", "Weather"),
            CamouflageProfile.camouflageProfiles.map { profile -> profile.name },
        )
    }
}
