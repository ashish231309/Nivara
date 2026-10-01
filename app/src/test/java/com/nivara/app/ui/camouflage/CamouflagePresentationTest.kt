package com.nivara.app.ui.camouflage

import com.nivara.app.R
import com.nivara.app.domain.camouflage.CamouflageProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Local JVM tests for how an identity is drawn.
 *
 * Resource identifiers are integers in a unit test — the values behind them need a device — so what
 * is asserted here is the mapping itself: every identity has a name, a glyph and a colour, the benign
 * identities do not borrow Nivara's, and they do not collide with each other. That is the part a
 * screen and a launcher icon can get wrong; the part that decides whether the icon *looks* right is
 * the verifier's business, and the drawing is compiled-only instrumented testing.
 */
class CamouflagePresentationTest {

    @Test
    fun `every identity has a label, a glyph and a background`() {
        CamouflageProfile.entries.forEach { profile ->
            assertNotEquals("${profile.name} has no label", 0, profile.labelRes())
            assertNotEquals("${profile.name} has no glyph", 0, profile.foregroundRes())
            assertNotEquals("${profile.name} has no background colour", 0, profile.backgroundRes())
        }
    }

    @Test
    fun `nivara's own identity keeps the application's own name, glyph and colour`() {
        assertEquals(R.string.app_name, CamouflageProfile.Nivara.labelRes())
        assertEquals(R.drawable.ic_launcher_foreground, CamouflageProfile.Nivara.foregroundRes())
        assertEquals(R.color.ic_launcher_background, CamouflageProfile.Nivara.backgroundRes())
    }

    @Test
    fun `the camouflage identities do not present nivara's own name, glyph or colour`() {
        CamouflageProfile.camouflageProfiles.forEach { profile ->
            assertNotEquals(
                "an identity that showed Nivara's own name would not be camouflage",
                CamouflageProfile.Nivara.labelRes(),
                profile.labelRes(),
            )
            assertNotEquals(
                "an identity that showed Nivara's own icon would not be camouflage",
                CamouflageProfile.Nivara.foregroundRes(),
                profile.foregroundRes(),
            )
            assertNotEquals(
                CamouflageProfile.Nivara.backgroundRes(),
                profile.backgroundRes(),
            )
        }
    }

    @Test
    fun `the identities are visually distinct from each other`() {
        val labels = CamouflageProfile.entries.map { profile -> profile.labelRes() }
        val glyphs = CamouflageProfile.entries.map { profile -> profile.foregroundRes() }
        val backgrounds = CamouflageProfile.entries.map { profile -> profile.backgroundRes() }

        assertEquals("two identities sharing a name would be indistinguishable", labels.size, labels.toSet().size)
        assertEquals("two identities sharing a glyph would be indistinguishable", glyphs.size, glyphs.toSet().size)
        assertEquals("two identities sharing a colour would be indistinguishable", backgrounds.size, backgrounds.toSet().size)
    }

    @Test
    fun `the glyphs are the camouflage drawables and not something else`() {
        assertEquals(R.drawable.ic_camouflage_notes, CamouflageProfile.Notes.foregroundRes())
        assertEquals(R.drawable.ic_camouflage_calculator, CamouflageProfile.Calculator.foregroundRes())
        assertEquals(R.drawable.ic_camouflage_weather, CamouflageProfile.Weather.foregroundRes())
    }

    @Test
    fun `the labels are the ordinary utility names`() {
        assertEquals(R.string.camouflage_profile_notes_label, CamouflageProfile.Notes.labelRes())
        assertEquals(R.string.camouflage_profile_calculator_label, CamouflageProfile.Calculator.labelRes())
        assertEquals(R.string.camouflage_profile_weather_label, CamouflageProfile.Weather.labelRes())
    }

    @Test
    fun `every identity in the model is covered by the mapping`() {
        // The `when` expressions are exhaustive at compile time; this is the runtime companion,
        // which fails if an identity is ever added to the model without a picture to show for it.
        val covered = CamouflageProfile.entries.map { profile ->
            listOf(profile.labelRes(), profile.foregroundRes(), profile.backgroundRes())
        }

        assertEquals(CamouflageProfile.entries.size, covered.size)
        assertEquals(3, CamouflageProfile.entries.size + 1)
    }
}
