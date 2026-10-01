package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultSearchQuery
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultTrashFailure
import com.nivara.app.domain.vault.VaultTrashItemStatus
import com.nivara.app.domain.vault.VaultTrashOrdering
import com.nivara.app.domain.vault.VaultTrashState
import com.nivara.app.domain.vault.VaultTrashUnreadable
import com.nivara.app.testing.testItem
import com.nivara.app.testing.testItemId
import com.nivara.app.testing.testTrashEntry
import com.nivara.app.ui.components.NivaraMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for how the trash is worded and mapped.
 *
 * Copy is behaviour here as much as anywhere else: an unreadable record must be explained as that, and
 * must never be drawn with the sentence that means "nothing is in the trash" — the sentence that would
 * tell somebody their files are gone when the record naming them is still on storage. Every state has
 * its own words, every failure has its own name, and no failure says a file was deleted.
 */
class VaultTrashPresentationTest {

    private fun everyState(): List<VaultTrashState> = listOf(
        VaultTrashState.Missing,
        VaultTrashState.Ready(listOf(testTrashEntry(seed = 1))),
        VaultTrashState.Ready(emptyList()),
        VaultTrashState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
        VaultTrashState.Unreadable(VaultTrashUnreadable.KeyUnavailable),
        VaultTrashState.UnsupportedVersion(fileVersion = 2),
        VaultTrashState.Unavailable,
        VaultTrashState.AccessDenied,
        VaultTrashState.VaultNotReady(VaultState.Missing),
    )

    private fun everyFailure(): List<VaultTrashFailure> = listOf(
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

    @Test
    fun every_state_maps_to_its_own_drawn_state() {
        val drawn = everyState().map { state ->
            state.toUiState(
                index = VaultIndexState.Missing,
                ordering = VaultTrashOrdering(),
                query = VaultSearchQuery.NONE,
            )
        }
        assertEquals(VaultTrashUiState.Empty, drawn[0])
        assertEquals(1, (drawn[1] as VaultTrashUiState.Trashed).items.size)
        assertEquals(VaultTrashUiState.Trashed(emptyList()), drawn[2])
        assertEquals(
            VaultTrashUiState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
            drawn[3],
        )
        assertEquals(
            VaultTrashUiState.Unreadable(VaultTrashUnreadable.KeyUnavailable),
            drawn[4],
        )
        assertEquals(VaultTrashUiState.UnsupportedVersion, drawn[5])
        assertEquals(VaultTrashUiState.Unavailable, drawn[6])
        assertEquals(VaultTrashUiState.AccessDenied, drawn[7])
        assertEquals(VaultTrashUiState.VaultNotReady, drawn[8])
    }

    @Test
    fun a_readable_state_has_no_title_and_no_body_of_its_own() {
        val drawn = VaultTrashState.Missing.toUiState(
            index = VaultIndexState.Missing,
            ordering = VaultTrashOrdering(),
            query = VaultSearchQuery.NONE,
        )
        assertNull(drawn.titleRes())
        assertNull(drawn.bodyRes())
    }

    @Test
    fun an_unreadable_record_is_never_worded_as_an_empty_trash() {
        val damaged = VaultTrashUiState.Unreadable(VaultTrashUnreadable.MetadataDamaged)
        val key = VaultTrashUiState.Unreadable(VaultTrashUnreadable.KeyUnavailable)
        assertEquals(R.string.vault_trash_unreadable_title, damaged.titleRes())
        assertEquals(R.string.vault_trash_unreadable_body, damaged.bodyRes())
        assertEquals(R.string.vault_trash_unreadable_title, key.titleRes())
        assertEquals(R.string.vault_trash_key_unavailable_body, key.bodyRes())
        listOf(damaged, key).forEach { state ->
            assertNotEquals(R.string.vault_trash_empty, state.bodyRes())
            assertNotEquals(R.string.vault_trash_empty, state.titleRes())
            assertFalse(state.acceptsChanges)
        }
    }

    @Test
    fun every_state_that_is_not_a_readable_one_refuses_changes() {
        listOf<VaultTrashUiState>(
            VaultTrashUiState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
            VaultTrashUiState.UnsupportedVersion,
            VaultTrashUiState.Unavailable,
            VaultTrashUiState.AccessDenied,
        ).forEach { state -> assertFalse(state.toString(), state.acceptsChanges) }
        assertTrue(VaultTrashUiState.Empty.acceptsChanges)
        assertTrue(VaultTrashUiState.Trashed(emptyList()).acceptsChanges)
    }

    @Test
    fun a_readable_record_is_drawn_as_its_entries_joined_with_the_index() {
        val item = testItem(seed = 1, name = "trip.jpg")
        val drawn = VaultTrashState.Ready(listOf(testTrashEntry(seed = 1))).toUiState(
            index = VaultIndexState.Ready(items = listOf(item)),
            ordering = VaultTrashOrdering(),
            query = VaultSearchQuery.NONE,
        )
        val row = (drawn as VaultTrashUiState.Trashed).items.single()
        assertEquals(testItemId(1), row.id)
        assertEquals("trip.jpg", row.item?.name)
        assertEquals(VaultTrashItemStatus.InVault, row.status)
        assertFalse(row.contentMissing)
    }

    @Test
    fun an_entry_the_index_no_longer_names_is_still_drawn() {
        val drawn = VaultTrashState.Ready(listOf(testTrashEntry(seed = 1))).toUiState(
            index = VaultIndexState.Ready(items = emptyList()),
            ordering = VaultTrashOrdering(),
            query = VaultSearchQuery.NONE,
        )
        val row = (drawn as VaultTrashUiState.Trashed).items.single()
        assertNull(row.item)
        assertEquals(VaultTrashItemStatus.NoLongerInVault, row.status)
    }

    @Test
    fun an_item_whose_content_is_missing_is_said_to_be_missing() {
        val drawn = VaultTrashState.Ready(listOf(testTrashEntry(seed = 1))).toUiState(
            index = VaultIndexState.Ready(
                items = listOf(testItem(seed = 1)),
                missingContent = setOf(testItemId(1)),
            ),
            ordering = VaultTrashOrdering(),
            query = VaultSearchQuery.NONE,
        )
        val row = (drawn as VaultTrashUiState.Trashed).items.single()
        assertTrue(row.contentMissing)
    }

    @Test
    fun the_trash_search_filters_over_the_joined_rows_only() {
        val index = VaultIndexState.Ready(
            items = listOf(testItem(seed = 1, name = "trip.jpg"), testItem(seed = 2, name = "invoice.pdf")),
        )
        val state = VaultTrashState.Ready(listOf(testTrashEntry(seed = 1), testTrashEntry(seed = 2)))
        val drawn = state.toUiState(
            index = index,
            ordering = VaultTrashOrdering(),
            query = VaultSearchQuery.of("trip"),
        )
        val rows = (drawn as VaultTrashUiState.Trashed).items
        assertEquals(listOf("trip.jpg"), rows.mapNotNull { row -> row.item?.name })
    }

    @Test
    fun every_failure_has_its_own_message_and_none_of_them_is_the_generic_one() {
        // The key being unavailable is one fact with one sentence, whether it surfaced while the
        // record was being read or while it was being written: those two failures share their words
        // on purpose, and everything else has its own.
        val failures = everyFailure().filterNot { failure -> failure == VaultTrashFailure.KeyUnavailable }
        val messages = failures.map { failure -> failure.asMessage() }
        assertEquals(failures.size, messages.distinct().size)
        assertEquals(
            VaultTrashFailure.TrashUnreadable(VaultTrashUnreadable.KeyUnavailable).asMessage(),
            VaultTrashFailure.KeyUnavailable.asMessage(),
        )
        messages.forEach { message ->
            assertNotEquals(vaultTrashChangeFailedMessage(), message)
            assertTrue(message.textRes != 0)
        }
        assertEquals(
            NivaraMessage(R.string.vault_trash_error_not_authorized),
            VaultTrashFailure.NotAuthorized.asMessage(),
        )
        assertEquals(
            NivaraMessage(R.string.vault_trash_error_not_verified),
            VaultTrashFailure.VerificationFailed.asMessage(),
        )
    }

    @Test
    fun the_two_screen_level_messages_are_not_each_other() {
        assertNotEquals(vaultTrashUnavailableMessage(), vaultTrashChangeFailedMessage())
    }

    @Test
    fun every_failure_message_is_generic_copy_without_a_path_or_a_name() {
        everyFailure().forEach { failure ->
            val message = failure.message.orEmpty()
            assertFalse(message, message.contains("content://"))
            assertFalse(message, message.contains("/"))
        }
    }
}
