package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.testing.testItem
import com.nivara.app.testing.testItemId
import com.nivara.app.testing.testTrashEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for what a trashed entry is and what joining it with the vault's list means.
 *
 * The properties held here are the promises the rest of the feature is built on: an entry is a
 * reference and a moment and nothing else, a record that cannot be read can never answer "nothing is
 * trashed", an identifier the list no longer names is *kept* rather than dropped, and a list that
 * cannot be read is not the same fact as an item the vault no longer has.
 */
class VaultTrashTest {

    private fun refuses(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (expected: IllegalArgumentException) {
        true
    }

    private fun unreadableStates(): List<VaultTrashState> = listOf(
        VaultTrashState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
        VaultTrashState.Unreadable(VaultTrashUnreadable.KeyUnavailable),
        VaultTrashState.UnsupportedVersion(fileVersion = 2),
        VaultTrashState.Unavailable,
        VaultTrashState.AccessDenied,
        VaultTrashState.VaultNotReady(VaultState.Missing),
    )

    // ------------------------------------------------------------------ the entry

    @Test
    fun an_entry_is_an_identifier_and_a_moment() {
        val entry = testTrashEntry(seed = 7, trashedAtEpochMillis = 1_700_000_000_123L)
        assertEquals(testItemId(7), entry.itemId)
        assertEquals(1_700_000_000_123L, entry.trashedAtEpochMillis)
    }

    @Test
    fun an_entry_refuses_a_moment_before_the_epoch() {
        assertTrue(refuses { testTrashEntry(seed = 7, trashedAtEpochMillis = -1L) })
        assertTrue(VaultTrashEntry(itemId = testItemId(7), trashedAtEpochMillis = 0L).trashedAtEpochMillis == 0L)
    }

    // ------------------------------------------------------------------ the record

    @Test
    fun a_vault_that_never_trashed_anything_accepts_changes_and_names_nothing() {
        val state = VaultTrashState.Missing
        assertTrue(state.acceptsChanges)
        assertEquals(emptySet<VaultItemId>(), state.trashedItemIdsOrNull)
    }

    @Test
    fun a_readable_record_answers_which_items_it_names() {
        val first = testTrashEntry(seed = 1)
        val second = testTrashEntry(seed = 2)
        val state = VaultTrashState.Ready(listOf(first, second))
        assertEquals(2, state.size)
        assertTrue(state.contains(first.itemId))
        assertEquals(second, state.entry(second.itemId))
        assertNull(state.entry(testItemId(9)))
        assertEquals(setOf(first.itemId, second.itemId), state.trashedItemIds)
        assertTrue(state.acceptsChanges)
    }

    @Test
    fun only_a_missing_record_or_a_readable_one_accepts_changes() {
        unreadableStates().forEach { state -> assertFalse(state.toString(), state.acceptsChanges) }
    }

    @Test
    fun a_state_that_cannot_be_read_has_no_trashed_set_at_all() {
        unreadableStates().forEach { state -> assertNull(state.toString(), state.trashedItemIdsOrNull) }
    }

    // ------------------------------------------------------------------ the join

    @Test
    fun an_entry_the_index_names_is_in_the_vault() {
        val item = testItem(seed = 3, name = "trip.jpg")
        val entry = testTrashEntry(seed = 3)
        val joined = entry.resolveAgainst(VaultIndexState.Ready(items = listOf(item)))
        assertEquals(VaultTrashItemStatus.InVault, joined.status)
        assertEquals(item, joined.item)
        assertTrue(joined.listReadable)
        assertEquals(entry, joined.entry)
        assertEquals(entry.itemId, joined.itemId)
        assertEquals(entry.trashedAtEpochMillis, joined.trashedAtEpochMillis)
    }

    @Test
    fun an_entry_the_index_does_not_name_stays_visible_as_missing() {
        val entry = testTrashEntry(seed = 4)
        val joined = entry.resolveAgainst(VaultIndexState.Ready(items = listOf(testItem(seed = 5))))
        assertEquals(VaultTrashItemStatus.NoLongerInVault, joined.status)
        assertNull(joined.item)
        assertTrue(joined.listReadable)
    }

    @Test
    fun an_entry_joined_with_an_unreadable_list_is_not_called_missing() {
        val entry = testTrashEntry(seed = 4)
        val joined = entry.resolveAgainst(
            VaultIndexState.Unreadable(VaultIndexUnreadable.MetadataDamaged),
        )
        assertEquals(VaultTrashItemStatus.VaultListUnreadable, joined.status)
        assertNull(joined.item)
        assertFalse(joined.listReadable)
    }

    @Test
    fun resolving_a_list_keeps_every_entry_in_order() {
        val entries = listOf(testTrashEntry(seed = 1), testTrashEntry(seed = 2))
        val joined = entries.resolveAgainst(VaultIndexState.Ready(items = emptyList()))
        assertEquals(2, joined.size)
        assertEquals(entries.map { entry -> entry.itemId }, joined.map { item -> item.itemId })
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun a_typed_trash_failure_reads_back_out_of_a_result() {
        val result: NivaraResult<Unit> = NivaraResult.Failure(VaultTrashFailure.ItemNotInVault)
        assertEquals(VaultTrashFailure.ItemNotInVault, result.error.asTrashFailure())
    }

    @Test
    fun a_foreign_throwable_is_not_a_trash_failure() {
        assertNull(IllegalStateException("elsewhere").asTrashFailure())
        assertNull((null as Throwable?).asTrashFailure())
    }

    @Test
    fun no_failure_says_a_file_was_deleted() {
        val failures = listOf(
            VaultTrashFailure.NotAuthorized,
            VaultTrashFailure.VaultNotReady(VaultState.Missing),
            VaultTrashFailure.TrashUnreadable(VaultTrashUnreadable.MetadataDamaged),
            VaultTrashFailure.TrashUnreadable(VaultTrashUnreadable.KeyUnavailable),
            VaultTrashFailure.UnsupportedVersion(fileVersion = 2),
            VaultTrashFailure.MetadataUnavailable,
            VaultTrashFailure.AccessDenied,
            VaultTrashFailure.IndexUnreadable(VaultIndexUnreadable.MetadataDamaged),
            VaultTrashFailure.IndexUnsupportedVersion(fileVersion = 2),
            VaultTrashFailure.IndexUnavailable,
            VaultTrashFailure.ItemNotInVault,
            VaultTrashFailure.TrashFull,
            VaultTrashFailure.StorageUnavailable,
            VaultTrashFailure.WriteFailed,
            VaultTrashFailure.VerificationFailed,
            VaultTrashFailure.KeyUnavailable,
            VaultTrashFailure.CryptographyFailed,
        )
        failures.forEach { failure ->
            val message = failure.message.orEmpty()
            assertFalse(message, message.contains("delete", ignoreCase = true))
        }
    }
}
