package com.nivara.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the navigation route table.
 *
 * Route strings are what the navigation graph matches on, so they must stay unique and
 * resolvable; a duplicate would silently shadow a screen.
 */
class NivaraDestinationTest {

    @Test
    fun `routes are unique and not blank`() {
        val routes = NivaraDestination.entries.map { it.route }

        assertEquals("duplicate navigation routes", routes.size, routes.toSet().size)
        assertTrue("blank navigation route", routes.none { it.isBlank() })
    }

    @Test
    fun `every destination can be resolved from its route`() {
        NivaraDestination.entries.forEach { destination ->
            assertEquals(destination, NivaraDestination.fromRoute(destination.route))
        }
    }

    @Test
    fun `unknown routes resolve to null`() {
        assertNull(NivaraDestination.fromRoute(null))
        assertNull(NivaraDestination.fromRoute("not-a-screen"))
    }
}
