package com.nivara.app.domain.launcher

import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the rule that decides what a launcher may draw.
 *
 * This is where the launcher's security-relevant decision lives, so the cases are exhaustive rather
 * than illustrative: every application is either drawn or withheld, the withheld ones are exactly
 * the stored set, and the two ways of failing to read that set produce no list at all.
 *
 * The rule is a pure function, which is why it can be tested like this — no device, no file, no
 * dispatcher. Everything that reads the platform is tested where it belongs, and the launcher's view
 * model is tested against these same outcomes.
 */
class LauncherCatalogueTest {

    private val camera = InstalledApplication("com.example.camera", "Camera")
    private val notes = InstalledApplication("com.example.notes", "Notes")
    private val maps = InstalledApplication("com.example.maps", "Maps")

    /** The same three applications in the domain's order: Camera, Maps, Notes. */
    private val catalogue = listOf(camera, maps, notes)

    private fun hidden(vararg packageNames: String): HiddenApplicationsRead =
        HiddenApplicationsRead.Available(packageNames.map { name -> HiddenApplication(name) }.toSet())

    private fun loaded(catalogue: LauncherCatalogue): LauncherCatalogue.Loaded =
        catalogue as LauncherCatalogue.Loaded

    @Test
    fun `a visible application is drawn`() {
        val result = loaded(launcherCatalogue(catalogue, hidden(), revealHidden = false))

        assertEquals(listOf("Camera", "Maps", "Notes"), result.entries.map { it.label })
        assertTrue(result.hidden.isEmpty())
        assertFalse(result.revealed)
    }

    @Test
    fun `a hidden application is omitted`() {
        val result = loaded(
            launcherCatalogue(catalogue, hidden("com.example.notes"), revealHidden = false),
        )

        assertEquals(listOf(camera, maps), result.entries)
        assertEquals(listOf(notes), result.hidden)
        assertEquals(1, result.hiddenCount)
        assertEquals(1, result.withheldCount)
    }

    @Test
    fun `hiding matches the package name exactly`() {
        val result = loaded(
            launcherCatalogue(catalogue, hidden("com.example.Camera"), revealHidden = false),
        )

        assertEquals(
            "a difference in case is a different package, so nothing is hidden",
            catalogue,
            result.entries,
        )
        assertTrue(result.hidden.isEmpty())
    }

    @Test
    fun `every application hidden leaves nothing to draw`() {
        val result = loaded(
            launcherCatalogue(
                catalogue,
                hidden("com.example.camera", "com.example.notes", "com.example.maps"),
                revealHidden = false,
            ),
        )

        assertTrue(result.entries.isEmpty())
        assertEquals(3, result.hiddenCount)
        assertEquals(3, result.withheldCount)
    }

    @Test
    fun `a device with no applications produces an empty catalogue, not a failure`() {
        val result = loaded(launcherCatalogue(emptyList(), hidden(), revealHidden = false))

        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun `a stored name that is not installed is neither drawn nor invented`() {
        val result = loaded(
            launcherCatalogue(
                catalogue,
                hidden("com.example.camera", "com.example.gone"),
                revealHidden = false,
            ),
        )

        assertEquals(listOf(maps, notes), result.entries)
        assertEquals(
            "the withheld list describes this device, so it counts discovered applications only",
            listOf(camera),
            result.hidden,
        )
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun `an unreadable stored set produces no list at all`() {
        val result = launcherCatalogue(catalogue, HiddenApplicationsRead.Unreadable, revealHidden = false)

        assertEquals(LauncherCatalogue.HiddenStateUnreadable, result)
    }

    @Test
    fun `an unavailable stored set produces no list at all`() {
        val result = launcherCatalogue(catalogue, HiddenApplicationsRead.Unavailable, revealHidden = false)

        assertEquals(LauncherCatalogue.HiddenStateUnavailable, result)
    }

    @Test
    fun `the two failures stay distinguishable`() {
        assertFalse(
            launcherCatalogue(catalogue, HiddenApplicationsRead.Unreadable, revealHidden = false) ==
                launcherCatalogue(catalogue, HiddenApplicationsRead.Unavailable, revealHidden = false),
        )
    }

    @Test
    fun `a reveal draws the hidden applications too`() {
        val result = loaded(
            launcherCatalogue(catalogue, hidden("com.example.notes"), revealHidden = true),
        )

        assertEquals(catalogue, result.entries)
        assertEquals(listOf(notes), result.hidden)
        assertTrue(result.revealed)
        assertEquals(
            "nothing is being withheld while a reveal is active",
            0,
            result.withheldCount,
        )
    }

    @Test
    fun `a reveal with nothing hidden changes nothing`() {
        val result = loaded(launcherCatalogue(catalogue, hidden(), revealHidden = true))

        assertEquals(catalogue, result.entries)
        assertEquals(0, result.hiddenCount)
        assertTrue(result.revealed)
    }

    @Test
    fun `a reveal never conjures up an application that is not installed`() {
        val result = loaded(
            launcherCatalogue(catalogue, hidden("com.example.gone"), revealHidden = true),
        )

        assertEquals(catalogue, result.entries)
        assertTrue(result.hidden.isEmpty())
    }

    @Test
    fun `the drawn order is the domain's application order, not the order the device reported`() {
        val discovered = listOf(maps, camera, notes)

        val result = loaded(launcherCatalogue(discovered, hidden(), revealHidden = false))

        assertEquals(listOf(camera, maps, notes), result.entries)
    }

    @Test
    fun `the withheld order is the domain's application order too`() {
        val discovered = listOf(maps, camera, notes)

        val result = loaded(
            launcherCatalogue(discovered, hidden("com.example.maps", "com.example.camera"), revealHidden = false),
        )

        assertEquals(listOf(camera, maps), result.hidden)
    }

    @Test
    fun `a reveal does not change what is stored`() {
        val stored = hidden("com.example.notes")

        val withoutReveal = loaded(launcherCatalogue(catalogue, stored, revealHidden = false))
        val withReveal = loaded(launcherCatalogue(catalogue, stored, revealHidden = true))

        assertEquals(
            "the withheld set is the same set in both cases; only drawing differs",
            withoutReveal.hidden,
            withReveal.hidden,
        )
        assertEquals(1, withReveal.hiddenCount)
    }

    @Test
    fun `duplicate stored names collapse, because a set of names has no duplicates`() {
        val stored = HiddenApplicationsRead.Available(
            setOf(HiddenApplication("com.example.notes"), HiddenApplication("com.example.notes")),
        )

        val result = loaded(launcherCatalogue(catalogue, stored, revealHidden = false))

        assertEquals(listOf(notes), result.hidden)
    }
}
