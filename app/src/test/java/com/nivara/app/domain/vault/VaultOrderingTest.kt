package com.nivara.app.domain.vault

import com.nivara.app.testing.testAlbum
import com.nivara.app.testing.testAlbumId
import com.nivara.app.testing.testItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the order a list is drawn in.
 *
 * Two properties matter beyond "the field is compared the right way round": the order is *total*, so
 * two items that look identical still come out the same way every time, and it is the *only* thing
 * that decides, so nothing about a person's usage can influence it. The tests below check both, for
 * every field, in both directions, and for the albums beside them.
 */
class VaultOrderingTest {

    private val alpha = testItem(seed = 1, name = "alpha.jpg", mimeType = "image/jpeg", sizeBytes = 300L, importedAtEpochMillis = 30L)
    private val bravo = testItem(seed = 2, name = "Bravo.mp4", mimeType = "video/mp4", sizeBytes = 100L, importedAtEpochMillis = 20L)
    private val charlie = testItem(seed = 3, name = "charlie.pdf", mimeType = "application/pdf", sizeBytes = 200L, importedAtEpochMillis = 10L)
    private val all = listOf(alpha, bravo, charlie)

    private fun names(items: List<VaultItem>): List<String> = items.map { item -> item.name }

    // ------------------------------------------------------------------ the default

    @Test
    fun `the default order is newest first, as the vault has always shown it`() {
        assertEquals(VaultSortField.ImportedAt, VaultOrdering.DEFAULT_FIELD)
        assertEquals(VaultSortDirection.Descending, VaultOrdering.DEFAULT_DIRECTION)

        val ordered = all.inOrder(VaultOrdering())

        assertEquals(listOf("alpha.jpg", "Bravo.mp4", "charlie.pdf"), names(ordered))
    }

    @Test
    fun `the default ordering does not depend on the order the items arrived in`() {
        val forward = all.inOrder(VaultOrdering())
        val backward = all.reversed().inOrder(VaultOrdering())

        assertEquals(forward, backward)
    }

    // ------------------------------------------------------------------ every field

    @Test
    fun `by name, case does not decide the order`() {
        val ascending = all.inOrder(VaultOrdering(field = VaultSortField.Name, direction = VaultSortDirection.Ascending))

        assertEquals(listOf("alpha.jpg", "Bravo.mp4", "charlie.pdf"), names(ascending))
    }

    @Test
    fun `by name descending reverses it`() {
        val descending = all.inOrder(VaultOrdering(field = VaultSortField.Name, direction = VaultSortDirection.Descending))

        assertEquals(listOf("charlie.pdf", "Bravo.mp4", "alpha.jpg"), names(descending))
    }

    @Test
    fun `by size`() {
        val ascending = all.inOrder(VaultOrdering(field = VaultSortField.Size, direction = VaultSortDirection.Ascending))
        val descending = all.inOrder(VaultOrdering(field = VaultSortField.Size, direction = VaultSortDirection.Descending))

        assertEquals(listOf("Bravo.mp4", "charlie.pdf", "alpha.jpg"), names(ascending))
        assertEquals(listOf("alpha.jpg", "charlie.pdf", "Bravo.mp4"), names(descending))
    }

    @Test
    fun `by import time`() {
        val ascending = all.inOrder(VaultOrdering(field = VaultSortField.ImportedAt, direction = VaultSortDirection.Ascending))

        assertEquals(listOf("charlie.pdf", "Bravo.mp4", "alpha.jpg"), names(ascending))
    }

    @Test
    fun `by kind, grouped the way a person expects, then by name`() {
        val ascending = all.inOrder(VaultOrdering(field = VaultSortField.Kind, direction = VaultSortDirection.Ascending))

        assertEquals(listOf("alpha.jpg", "Bravo.mp4", "charlie.pdf"), names(ascending))
    }

    @Test
    fun `every field produces a total order over every item`() {
        VaultSortField.entries.forEach { field ->
            VaultSortDirection.entries.forEach { direction ->
                val ordering = VaultOrdering(field = field, direction = direction)
                val ordered = all.inOrder(ordering)

                assertEquals("an ordering loses no item: $ordering", all.size, ordered.size)
                assertEquals("and invents none: $ordering", all.toSet(), ordered.toSet())
                assertEquals(
                    "sorting twice is the same as sorting once: $ordering",
                    ordered,
                    ordered.asReversed().inOrder(ordering),
                )
            }
        }
    }

    // ------------------------------------------------------------------ the tie-break

    @Test
    fun `two items that look identical come out in identifier order, both ways round`() {
        val first = testItem(seed = 20, name = "same.txt", sizeBytes = 5L, importedAtEpochMillis = 7L)
        val second = testItem(seed = 10, name = "same.txt", sizeBytes = 5L, importedAtEpochMillis = 7L)
        val input = listOf(first, second)

        VaultSortField.entries.forEach { field ->
            val ascending = input.inOrder(VaultOrdering(field = field, direction = VaultSortDirection.Ascending))
            val descending = input.inOrder(VaultOrdering(field = field, direction = VaultSortDirection.Descending))

            assertEquals("a tie is broken by the identifier: $field", listOf(second, first), ascending)
            assertEquals("and the reverse direction reverses it: $field", listOf(first, second), descending)
            assertEquals(
                "the order does not depend on the input order: $field",
                ascending,
                input.reversed().inOrder(VaultOrdering(field = field, direction = VaultSortDirection.Ascending)),
            )
        }
    }

    @Test
    fun `the identifier tie-break is the last word, so the order is total`() {
        val items = listOf(
            testItem(seed = 3, name = "x", sizeBytes = 1L, importedAtEpochMillis = 1L),
            testItem(seed = 1, name = "x", sizeBytes = 1L, importedAtEpochMillis = 1L),
            testItem(seed = 2, name = "x", sizeBytes = 1L, importedAtEpochMillis = 1L),
        )

        val ordered = items.inOrder(VaultOrdering(field = VaultSortField.Name, direction = VaultSortDirection.Ascending))

        assertEquals("00000000000000000000000000000001", ordered[0].id.value)
        assertEquals("00000000000000000000000000000002", ordered[1].id.value)
        assertEquals("00000000000000000000000000000003", ordered[2].id.value)
    }

    @Test
    fun `reversing keeps the identifier as the tie-break rather than reordering arbitrarily`() {
        val items = listOf(
            testItem(seed = 1, name = "x", sizeBytes = 1L, importedAtEpochMillis = 1L),
            testItem(seed = 2, name = "x", sizeBytes = 1L, importedAtEpochMillis = 1L),
        )

        val ascending = items.inOrder(VaultOrdering(field = VaultSortField.Size, direction = VaultSortDirection.Ascending))
        val descending = items.inOrder(VaultOrdering(field = VaultSortField.Size, direction = VaultSortDirection.Descending))

        assertEquals(listOf(items[0], items[1]), ascending)
        assertEquals(listOf(items[1], items[0]), descending)
    }

    // ------------------------------------------------------------------ the ordering value itself

    @Test
    fun `choosing a field keeps the direction and toggling keeps the field`() {
        val ordering = VaultOrdering()

        assertEquals(VaultSortField.Name, ordering.copy(field = VaultSortField.Name).field)
        assertEquals(VaultSortDirection.Descending, ordering.copy(field = VaultSortField.Name).direction)
        assertEquals(VaultSortField.ImportedAt, ordering.toggled().field)
        assertEquals(VaultSortDirection.Ascending, ordering.toggled().direction)
        assertEquals("toggling twice is where it started", ordering, ordering.toggled().toggled())
    }

    @Test
    fun `sorting does not change the list it was given`() {
        val input = all.toMutableList()

        input.toList().inOrder(VaultOrdering(field = VaultSortField.Name))

        assertEquals(all, input)
    }

    @Test
    fun `an empty list sorts to an empty list`() {
        assertTrue(emptyList<VaultItem>().inOrder(VaultOrdering()).isEmpty())
    }

    // ------------------------------------------------------------------ albums

    @Test
    fun `albums are ordered by title, case-insensitively`() {
        val albums = listOf(testAlbum(seed = 1, name = "zebra"), testAlbum(seed = 2, name = "Apples"))

        val ordered = albums.sortedWith(defaultAlbumOrder)

        assertEquals(listOf("Apples", "zebra"), ordered.map { album -> album.name })
    }

    @Test
    fun `two albums with the same title come out in identifier order`() {
        val albums = listOf(testAlbum(seed = 9, name = "Trip"), testAlbum(seed = 4, name = "Trip"))

        val ordered = albums.sortedWith(defaultAlbumOrder)

        assertEquals(listOf(testAlbumId(4), testAlbumId(9)), ordered.map { album -> album.id })
    }

    @Test
    fun `the album order is total and does not depend on how the record was written`() {
        val albums = listOf(testAlbum(seed = 3, name = "b"), testAlbum(seed = 1, name = "a"), testAlbum(seed = 2, name = "a"))

        val ordered = albums.sortedWith(defaultAlbumOrder)

        assertEquals(ordered, albums.reversed().sortedWith(defaultAlbumOrder))
        assertEquals(listOf(testAlbumId(1), testAlbumId(2), testAlbumId(3)), ordered.map { album -> album.id })
    }
}
