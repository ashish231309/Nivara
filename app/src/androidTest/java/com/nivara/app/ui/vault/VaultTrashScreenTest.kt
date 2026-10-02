package com.nivara.app.ui.vault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultTrashItemStatus
import com.nivara.app.domain.vault.VaultTrashUnreadable
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the trash the vault screen draws.
 *
 * The stateless screen is handed a state and the taps it reports are observed: no repository, no
 * storage, no key and no file is involved. What is verified is the composition — that a trashed file is
 * listed with its details and one restore action, that the explanation says in as many words that the
 * file is not deleted, and that a record which cannot be read is never drawn as an empty trash.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. CI compiles this suite; it is executed only
 * when a device is attached, so nothing here is claimed as verified until it has actually run.
 */
@RunWith(AndroidJUnit4::class)
class VaultTrashScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context.getString(id)

    private val itemId = VaultItemId("00000000000000000000000000000001")

    private fun item(name: String = "holiday.jpg") = VaultItemUi(
        id = itemId,
        name = name,
        kind = VaultContentKind.Image,
        sizeBytes = 4_096L,
        importedAtEpochMillis = 1_700_000_000_000L,
        mimeType = "image/jpeg",
    )

    private fun state(trash: VaultTrashUiState) = VaultUiState.Ready(
        vault = VaultState.Ready(
            identity = VaultIdentity("00112233445566778899aabbccddeeff"),
            formatVersion = 1,
        ),
        index = VaultIndexUiState.Empty,
        trash = trash,
        section = VaultSection.Trash,
        sessionAuthenticated = true,
    )

    private fun draw(
        trash: VaultTrashUiState,
        onRestoreItem: (VaultItemId) -> Unit = {},
    ) {
        rule.setContent {
            NivaraTheme {
                VaultScreen(
                    uiState = state(trash),
                    onChooseRoot = {},
                    onImport = {},
                    onOpenItem = {},
                    onInitialize = {},
                    onReplaceUnreadable = {},
                    onRetry = {},
                    onUnlock = {},
                    onMessageShown = {},
                    onSectionSelected = {},
                    onSearchQueryChanged = {},
                    onSearchCleared = {},
                    onSortFieldSelected = {},
                    onSortDirectionToggled = {},
                    onAlbumOpened = {},
                    onAlbumClosed = {},
                    onAlbumCreated = {},
                    onAlbumRenameStarted = {},
                    onAlbumRenameCancelled = {},
                    onAlbumRenameConfirmed = { _, _ -> },
                    onAlbumDeleteRequested = {},
                    onAlbumDeleteCancelled = {},
                    onAlbumDeleteConfirmed = {},
                    onAlbumItemsEditingChanged = {},
                    onAlbumItemAdded = {},
                    onAlbumItemRemoved = {},
                    onRestoreItem = onRestoreItem,
                )
            }
        }
    }

    @Test
    fun a_trashed_file_is_listed_with_its_details_and_one_restore_action() {
        draw(
            VaultTrashUiState.Trashed(
                listOf(
                    VaultTrashItemUi(
                        id = itemId,
                        trashedAtEpochMillis = 1_700_000_000_000L,
                        item = item(),
                        status = VaultTrashItemStatus.InVault,
                        contentMissing = false,
                    ),
                ),
            ),
        )
        rule.onNodeWithText("holiday.jpg").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_trash_restore_action)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_trash_explanation)).assertIsDisplayed()
    }

    @Test
    fun the_restore_action_reports_the_item_it_names() {
        var restored: VaultItemId? = null
        draw(
            VaultTrashUiState.Trashed(
                listOf(
                    VaultTrashItemUi(
                        id = itemId,
                        trashedAtEpochMillis = 1_700_000_000_000L,
                        item = item(),
                        status = VaultTrashItemStatus.InVault,
                        contentMissing = false,
                    ),
                ),
            ),
            onRestoreItem = { restored = it },
        )
        rule.onNodeWithText(string(R.string.vault_trash_restore_action)).performClick()
        assertEquals(itemId, restored)
    }

    @Test
    fun an_empty_trash_says_it_is_empty_and_says_nothing_was_deleted() {
        draw(VaultTrashUiState.Empty)
        rule.onNodeWithText(string(R.string.vault_trash_empty)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_trash_explanation)).assertIsDisplayed()
    }

    @Test
    fun a_record_that_cannot_be_read_is_never_drawn_as_an_empty_trash() {
        draw(VaultTrashUiState.Unreadable(VaultTrashUnreadable.MetadataDamaged))
        rule.onNodeWithText(string(R.string.vault_trash_unreadable_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_trash_unreadable_body)).assertIsDisplayed()
    }

    @Test
    fun an_entry_the_vault_no_longer_lists_says_so_rather_than_hiding() {
        draw(
            VaultTrashUiState.Trashed(
                listOf(
                    VaultTrashItemUi(
                        id = itemId,
                        trashedAtEpochMillis = 1_700_000_000_000L,
                        item = null,
                        status = VaultTrashItemStatus.NoLongerInVault,
                        contentMissing = false,
                    ),
                ),
            ),
        )
        rule.onNodeWithText(string(R.string.vault_trash_row_missing_from_list)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_trash_restore_action)).assertIsDisplayed()
    }
}
