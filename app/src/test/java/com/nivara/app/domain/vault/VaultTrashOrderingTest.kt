package com.nivara.app.domain.vault

import com.nivara.app.testing.testItem
import com.nivara.app.testing.testTrashEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the trash list's order.
 *
 * Every order must be total and reproducible: two files trashed in the same millisecond still come out
 * in a fixed sequence, an entry the list cannot resolve sorts after the ones it can, and nothing here
 * consults a clock, a usage record or anything else that changes on its own.
 */
class VaultTrashOrderingTest {

    private fun resolved(
        seed: Int,
        item: VaultItem?,
        trashedAt: Long = 1_700_000_000_000L + seed,
    ): VaultTrashItem =
        testTrashEntry(seed = seed, trashedAtEpochMillis = trashedAt).resolveAgainst(
            if (item == null) {
                VaultIndexState.Ready(items = emptyList())
            } else {
                VaultIndexState.Ready(items = listOf(item))
            },
        )

    private fun ordered(
        items: List<VaultTrashItem>,
        field: VaultTrashSortField,
        direction: VaultSortDirection = VaultSortDirection.Ascending,
    ): List<VaultTrashItem> = items.inTrashOrder(VaultTrashOrdering(field = field, direction = direction))

    private fun ids(items: List<VaultTrashItem>): List<String> = items.map { item -> item.itemId.value }

    @Test
    fun the_default_order_is_the_most_recently_trashed_first() {
        val ordering = VaultTrashOrdering()
        assertEquals(VaultTrashSortField.TrashedAt, ordering.field)
        assertEquals(VaultSortDirection.Descending, ordering.direction)
    }

    @Test
    fun toggled_reverses_the_direction_and_keeps_the_field() {
        val toggled = VaultTrashOrdering(field = VaultTrashSortField.Name).toggled()
        assertEquals(VaultTrashSortField.Name, toggled.field)
        assertEquals(VaultSortDirection.Ascending, toggled.direction)
    }

    @Test
    fun trashed_at_orders_by_the_moment() {
        val older = resolved(seed = 1, item = testItem(seed = 1), trashedAt = 100L)
        val newer = resolved(seed = 2, item = testItem(seed = 2), trashedAt = 200L)
        assertEquals(
            ids(listOf(newer, older)),
            ids(ordered(listOf(older, newer), VaultTrashSortField.TrashedAt, VaultSortDirection.Descending)),
        )
        assertEquals(
            ids(listOf(older, newer)),
            ids(ordered(listOf(newer, older), VaultTrashSortField.TrashedAt)),
        )
    }

    @Test
    fun name_compares_case_insensitively() {
        val apple = resolved(seed = 1, item = testItem(seed = 1, name = "Apple.txt"))
        val banana = resolved(seed = 2, item = testItem(seed = 2, name = "banana.txt"))
        assertEquals(
            ids(listOf(apple, banana)),
            ids(ordered(listOf(banana, apple), VaultTrashSortField.Name)),
        )
    }

    @Test
    fun size_orders_by_bytes() {
        val small = resolved(seed = 1, item = testItem(seed = 1, sizeBytes = 10L))
        val large = resolved(seed = 2, item = testItem(seed = 2, sizeBytes = 20L))
        assertEquals(
            ids(listOf(small, large)),
            ids(ordered(listOf(large, small), VaultTrashSortField.Size)),
        )
    }

    @Test
    fun imported_at_orders_by_when_the_file_arrived() {
        val older = resolved(seed = 1, item = testItem(seed = 1, importedAtEpochMillis = 100L))
        val newer = resolved(seed = 2, item = testItem(seed = 2, importedAtEpochMillis = 200L))
        assertEquals(
            ids(listOf(older, newer)),
            ids(ordered(listOf(newer, older), VaultTrashSortField.ImportedAt)),
        )
    }

    @Test
    fun kind_groups_pictures_before_documents() {
        val picture = resolved(seed = 1, item = testItem(seed = 1, mimeType = "image/jpeg"))
        val document = resolved(seed = 2, item = testItem(seed = 2, mimeType = "text/plain"))
        assertEquals(
            ids(listOf(picture, document)),
            ids(ordered(listOf(document, picture), VaultTrashSortField.Kind)),
        )
    }

    @Test
    fun an_entry_the_list_cannot_resolve_sorts_after_one_it_can() {
        val unknown = resolved(seed = 1, item = null)
        val known = resolved(seed = 2, item = testItem(seed = 2))
        assertEquals(
            ids(listOf(known, unknown)),
            ids(ordered(listOf(unknown, known), VaultTrashSortField.Name)),
        )
    }

    @Test
    fun two_entries_that_compare_equal_are_ordered_by_identifier() {
        val first = resolved(seed = 1, item = testItem(seed = 8, name = "same.txt"), trashedAt = 5L)
        val second = resolved(seed = 2, item = testItem(seed = 9, name = "same.txt"), trashedAt = 5L)
        assertEquals(
            ids(listOf(first, second)),
            ids(ordered(listOf(second, first), VaultTrashSortField.TrashedAt)),
        )
    }

    @Test
    fun the_order_is_total_and_reproducible() {
        val items = listOf(
            resolved(seed = 3, item = testItem(seed = 3, name = "c.txt"), trashedAt = 3L),
            resolved(seed = 1, item = testItem(seed = 1, name = "a.txt"), trashedAt = 1L),
            resolved(seed = 2, item = null, trashedAt = 2L),
        )
        val first = ids(ordered(items, VaultTrashSortField.Name))
        val second = ids(ordered(items.reversed(), VaultTrashSortField.Name))
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun the_default_order_puts_the_newest_first() {
        val older = resolved(seed = 1, item = testItem(seed = 1), trashedAt = 10L)
        val newer = resolved(seed = 2, item = testItem(seed = 2), trashedAt = 20L)
        assertEquals(
            ids(listOf(newer, older)),
            ids(listOf(older, newer).inTrashOrder(VaultTrashOrdering())),
        )
    }

    @Test
    fun every_field_and_direction_produces_the_same_order_for_the_same_input() {
        val items = listOf(
            resolved(seed = 5, item = testItem(seed = 5, name = "e.txt"), trashedAt = 5L),
            resolved(seed = 6, item = testItem(seed = 6, name = "f.txt"), trashedAt = 6L),
        )
        VaultTrashSortField.entries.forEach { field ->
            VaultSortDirection.entries.forEach { direction ->
                val ordering = VaultTrashOrdering(field = field, direction = direction)
                assertEquals(
                    "$field $direction must be a total order",
                    ids(items.inTrashOrder(ordering)),
                    ids(items.reversed().inTrashOrder(ordering)),
                )
            }
        }
    }
}
