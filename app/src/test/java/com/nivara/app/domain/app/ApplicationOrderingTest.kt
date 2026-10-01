package com.nivara.app.domain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the default display order.
 *
 * The order is the app's promise that an unchanged device produces the same list in the same
 * sequence, so it is tested as a property: the same set always sorts the same way, whatever order
 * it arrives in.
 */
class ApplicationOrderingTest {

    @Test
    fun `labels are compared case-insensitively`() {
        val applications = listOf(
            InstalledApplication("com.example.zebra", "Zebra"),
            InstalledApplication("com.example.apple", "apple"),
            InstalledApplication("com.example.banana", "banana"),
        )

        assertEquals(
            listOf("apple", "banana", "Zebra"),
            applications.inDefaultApplicationOrder().map { it.label },
        )
    }

    @Test
    fun `the package name breaks a tie between identical labels`() {
        val applications = listOf(
            InstalledApplication("com.zeta.notes", "Notes"),
            InstalledApplication("com.alpha.notes", "Notes"),
        )

        assertEquals(
            listOf("com.alpha.notes", "com.zeta.notes"),
            applications.inDefaultApplicationOrder().map { it.packageName },
        )
    }

    @Test
    fun `labels that differ only by case are ordered by package name`() {
        val applications = listOf(
            InstalledApplication("com.example.notes.b", "notes"),
            InstalledApplication("com.example.notes.a", "Notes"),
        )

        assertEquals(
            listOf("com.example.notes.a", "com.example.notes.b"),
            applications.inDefaultApplicationOrder().map { it.packageName },
        )
    }

    @Test
    fun `the order does not depend on the input order`() {
        val applications = listOf(
            InstalledApplication("com.example.camera", "Camera"),
            InstalledApplication("com.example.notes", "Notes"),
            InstalledApplication("com.example.alarm", "Alarm"),
            InstalledApplication("com.example.maps", "Maps"),
        )

        val expected = applications.inDefaultApplicationOrder()

        assertEquals(expected, applications.reversed().inDefaultApplicationOrder())
        assertEquals(expected, listOf(applications[2], applications[0], applications[3], applications[1])
            .inDefaultApplicationOrder())
    }

    @Test
    fun `sorting leaves the receiver untouched`() {
        val applications = listOf(
            InstalledApplication("com.example.notes", "Notes"),
            InstalledApplication("com.example.alarm", "Alarm"),
        )

        applications.inDefaultApplicationOrder()

        assertEquals(listOf("Notes", "Alarm"), applications.map { it.label })
    }

    @Test
    fun `an empty list stays empty`() {
        assertTrue(emptyList<InstalledApplication>().inDefaultApplicationOrder().isEmpty())
    }

    @Test
    fun `the reverse direction mirrors the default one`() {
        val applications = listOf(
            InstalledApplication("com.example.camera", "Camera"),
            InstalledApplication("com.example.notes", "Notes"),
            InstalledApplication("com.example.alarm", "Alarm"),
        )

        assertEquals(
            applications.inDefaultApplicationOrder().reversed(),
            applications.inReverseApplicationOrder(),
        )
    }

    @Test
    fun `the reverse direction also orders identical labels by package name`() {
        val applications = listOf(
            InstalledApplication("com.zeta.notes", "Notes"),
            InstalledApplication("com.alpha.notes", "Notes"),
        )

        // The comparator is reversed whole, so the tie-breaker reverses with it: identical labels
        // still come out in a fixed sequence, which is what keeps the order total.
        assertEquals(
            listOf("com.zeta.notes", "com.alpha.notes"),
            applications.inReverseApplicationOrder().map { it.packageName },
        )
    }

    @Test
    fun `labels are compared case-insensitively in the reverse direction too`() {
        val applications = listOf(
            InstalledApplication("com.example.zebra", "Zebra"),
            InstalledApplication("com.example.apple", "apple"),
            InstalledApplication("com.example.banana", "banana"),
        )

        assertEquals(
            listOf("Zebra", "banana", "apple"),
            applications.inReverseApplicationOrder().map { it.label },
        )
    }

    @Test
    fun `reverse sorting leaves the receiver untouched`() {
        val applications = listOf(
            InstalledApplication("com.example.notes", "Notes"),
            InstalledApplication("com.example.alarm", "Alarm"),
        )

        applications.inReverseApplicationOrder()

        assertEquals(listOf("Notes", "Alarm"), applications.map { it.label })
    }

    @Test
    fun `an empty list stays empty in the reverse direction`() {
        assertTrue(emptyList<InstalledApplication>().inReverseApplicationOrder().isEmpty())
    }

    @Test
    fun `the comparator agrees with the list helper`() {
        val applications = listOf(
            InstalledApplication("com.example.b", "same"),
            InstalledApplication("com.example.a", "Same"),
        )

        assertEquals(
            applications.sortedWith(defaultApplicationOrder),
            applications.inDefaultApplicationOrder(),
        )
    }
}
