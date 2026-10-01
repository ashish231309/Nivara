package com.nivara.app.domain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the future search rule.
 *
 * There is no search field yet; what is tested is the matching rule the App Lock screen will use,
 * so that adding the field later cannot change which applications a query is expected to find.
 * Nothing here performs a search over a device — the input is an ordinary list.
 */
class ApplicationSearchTest {

    private val camera = InstalledApplication("com.example.camera", "Camera")
    private val notes = InstalledApplication("com.example.notes", "Notes and lists")
    private val applications = listOf(camera, notes)

    @Test
    fun `normalising trims and lowercases`() {
        assertEquals("camera", ApplicationSearch.normalize("  CamEra  "))
        assertEquals("", ApplicationSearch.normalize("   "))
        assertEquals("", ApplicationSearch.normalize(""))
    }

    @Test
    fun `an empty query matches every application`() {
        assertTrue(ApplicationSearch.matches(camera, ApplicationSearch.normalize("")))
        assertEquals(applications, ApplicationSearch.filter(applications, ""))
    }

    @Test
    fun `a whitespace-only query matches every application`() {
        assertEquals(applications, ApplicationSearch.filter(applications, "   "))
    }

    @Test
    fun `the label is matched case-insensitively and partially`() {
        assertEquals(listOf(camera), ApplicationSearch.filter(applications, "cam"))
        assertEquals(listOf(camera), ApplicationSearch.filter(applications, "CAMERA"))
    }

    @Test
    fun `the package name is matched too`() {
        assertEquals(listOf(notes), ApplicationSearch.filter(applications, "com.example.notes"))
        assertEquals(listOf(notes), ApplicationSearch.filter(applications, "NOTES"))
    }

    @Test
    fun `a query that matches nothing returns nothing`() {
        assertFalse(ApplicationSearch.matches(camera, ApplicationSearch.normalize("vault")))
        assertTrue(ApplicationSearch.filter(applications, "vault").isEmpty())
    }

    @Test
    fun `filtering keeps the order it was given`() {
        val reordered = listOf(notes, camera)

        assertEquals(listOf(notes, camera), ApplicationSearch.filter(reordered, "e"))
    }
}
