package com.nivara.app.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultOrganizationUnreadable
import com.nivara.app.domain.vault.VaultSortField
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the albums the vault screen draws.
 *
 * The stateless screen is handed a state and the taps it reports are observed: no repository, no
 * storage, no key and no file is involved in any test here. What is verified is the composition — that
 * the albums are listed with their titles and counts, that a record which cannot be read is never
 * drawn as a vault without albums, that deleting asks first and says in as many words that the files
 * are untouched, that a reference the vault no longer lists is shown rather than hidden, and that the
 * one way into a file is the same row whichever collection it was found in.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. CI compiles this suite; it is executed only
 * when a device is attached, so nothing here is claimed as verified until it has actually run.
 */
@RunWith(AndroidJUnit4::class)
class VaultAlbumsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context.getString(id)

    private fun string(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)

    private val albumId = VaultAlbumId("10000000000000000000000000000001")
    private val otherAlbumId = VaultAlbumId("10000000000000000000000000000002")
    private val memberId = VaultItemId("00000000000000000000000000000001")
    private val staleId = VaultItemId("00000000000000000000000000000404")

    private fun item(name: String = "holiday.jpg") = VaultItemUi(
        id = memberId,
        name = name,
        kind = VaultContentKind.Image,
        sizeBytes = 4_096L,
        importedAtEpochMillis = 1_700_000_000_000L,
        mimeType = "image/jpeg",
    )

    private fun album(
        id: VaultAlbumId = albumId,
        name: String = "Trip",
        memberCount: Int = 1,
        staleCount: Int = 0,
        resolved: Boolean = true,
    ) = VaultAlbumUi(
        id = id,
        name = name,
        createdAtEpochMillis = 1_700_000_000_000L,
        memberCount = memberCount,
        staleCount = staleCount,
        resolved = resolved,
    )

    private fun state(
        organization: VaultOrganizationUiState,
        index: VaultIndexUiState = VaultIndexUiState.Indexed(items = listOf(item())),
        openAlbum: VaultAlbumDetailUi? = null,
        renamingAlbumId: VaultAlbumId? = null,
        confirmingAlbumDeleteId: VaultAlbumId? = null,
        editingAlbumItems: Boolean = false,
        search: VaultSearchUiState = VaultSearchUiState.NotAsked,
        searchQuery: String = "",
    ) = VaultUiState.Ready(
        vault = VaultState.Ready(
            identity = VaultIdentity("00112233445566778899aabbccddeeff"),
            formatVersion = 1,
        ),
        index = index,
        organization = organization,
        section = VaultSection.Albums,
        searchQuery = searchQuery,
        search = search,
        openAlbum = openAlbum,
        renamingAlbumId = renamingAlbumId,
        confirmingAlbumDeleteId = confirmingAlbumDeleteId,
        editingAlbumItems = editingAlbumItems,
        sessionAuthenticated = true,
    )

    private fun draw(
        state: VaultUiState,
        onAlbumCreated: (String) -> Unit = {},
        onAlbumOpened: (VaultAlbumId) -> Unit = {},
        onAlbumRenameStarted: (VaultAlbumId) -> Unit = {},
        onAlbumRenameCancelled: () -> Unit = {},
        onAlbumRenameConfirmed: (VaultAlbumId, String) -> Unit = { _, _ -> },
        onAlbumDeleteRequested: (VaultAlbumId) -> Unit = {},
        onAlbumDeleteCancelled: () -> Unit = {},
        onAlbumDeleteConfirmed: (VaultAlbumId) -> Unit = {},
        onAlbumItemRemoved: (VaultItemId) -> Unit = {},
        onAlbumItemAdded: (VaultItemId) -> Unit = {},
        onSectionSelected: (VaultSection) -> Unit = {},
        onSortFieldSelected: (VaultSortField) -> Unit = {},
    ) {
        rule.setContent {
            NivaraTheme {
                VaultScreen(
                    uiState = state,
                    onChooseRoot = {},
                    onImport = {},
                    onOpenItem = {},
                    onInitialize = {},
                    onReplaceUnreadable = {},
                    onRetry = {},
                    onUnlock = {},
                    onMessageShown = {},
                    onSectionSelected = onSectionSelected,
                    onSearchQueryChanged = {},
                    onSearchCleared = {},
                    onSortFieldSelected = onSortFieldSelected,
                    onSortDirectionToggled = {},
                    onAlbumOpened = onAlbumOpened,
                    onAlbumClosed = {},
                    onAlbumCreated = onAlbumCreated,
                    onAlbumRenameStarted = onAlbumRenameStarted,
                    onAlbumRenameCancelled = onAlbumRenameCancelled,
                    onAlbumRenameConfirmed = onAlbumRenameConfirmed,
                    onAlbumDeleteRequested = onAlbumDeleteRequested,
                    onAlbumDeleteCancelled = onAlbumDeleteCancelled,
                    onAlbumDeleteConfirmed = onAlbumDeleteConfirmed,
                    onAlbumItemsEditingChanged = {},
                    onAlbumItemAdded = onAlbumItemAdded,
                    onAlbumItemRemoved = onAlbumItemRemoved,
                )
            }
        }
    }

    // ------------------------------------------------------------------ the list

    @Test
    fun the_albums_are_listed_with_their_titles_and_counts() {
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(
                    listOf(album(), album(id = otherAlbumId, name = "Winter", memberCount = 2)),
                ),
            ),
        )

        rule.onNodeWithText("Trip").assertIsDisplayed()
        rule.onNodeWithText("Winter").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_member_count_format, 1)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_member_count_format, 2)).assertIsDisplayed()
    }

    @Test
    fun a_vault_nobody_has_organised_says_so_and_offers_a_field() {
        draw(state(organization = VaultOrganizationUiState.Empty))

        rule.onNodeWithText(string(R.string.vault_albums_none)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_name_label), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_create_action)).assertIsNotEnabled()
    }

    @Test
    fun a_record_that_cannot_be_read_is_explained_and_never_drawn_as_an_empty_list() {
        draw(
            state(
                organization = VaultOrganizationUiState.Unreadable(
                    VaultOrganizationUnreadable.MetadataDamaged,
                ),
            ),
        )

        rule.onNodeWithText(string(R.string.vault_albums_unreadable_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_albums_none)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.vault_album_create_action)).assertDoesNotExist()
    }

    @Test
    fun a_record_from_a_newer_nivara_is_explained_in_its_own_words() {
        draw(state(organization = VaultOrganizationUiState.UnsupportedVersion))

        rule.onNodeWithText(string(R.string.vault_albums_unsupported_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_albums_unsupported_body)).assertIsDisplayed()
    }

    @Test
    fun an_album_is_opened_from_its_title() {
        var opened: VaultAlbumId? = null
        draw(
            state(organization = VaultOrganizationUiState.Albums(listOf(album()))),
            onAlbumOpened = { id -> opened = id },
        )

        rule.onNodeWithText("Trip").performClick()

        assertEquals(albumId, opened)
    }

    // ------------------------------------------------------------------ renaming and deleting

    @Test
    fun the_album_row_carries_the_two_organisational_actions() {
        draw(state(organization = VaultOrganizationUiState.Albums(listOf(album()))))

        rule.onNodeWithText(string(R.string.vault_album_rename_action)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_delete_action)).assertIsDisplayed()
    }

    @Test
    fun renaming_offers_the_current_title_and_can_be_abandoned() {
        var cancelled = false
        draw(
            state(organization = VaultOrganizationUiState.Albums(listOf(album())), renamingAlbumId = albumId),
            onAlbumRenameCancelled = { cancelled = true },
        )

        rule.onNodeWithText("Trip").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_rename_confirm)).assertIsEnabled()

        rule.onNodeWithText(string(R.string.vault_album_cancel_action)).performClick()

        assertEquals(true, cancelled)
    }

    @Test
    fun deleting_an_album_asks_first_and_says_what_it_does_not_delete() {
        var requested: VaultAlbumId? = null
        var confirmed: VaultAlbumId? = null
        draw(
            state(organization = VaultOrganizationUiState.Albums(listOf(album()))),
            onAlbumDeleteRequested = { id -> requested = id },
            onAlbumDeleteConfirmed = { id -> confirmed = id },
        )

        rule.onNodeWithText(string(R.string.vault_album_delete_action)).performClick()

        assertEquals(albumId, requested)
        assertNull("asking is not deleting", confirmed)
    }

    @Test
    fun a_pending_deletion_says_the_files_are_untouched_and_waits_for_the_confirmation() {
        var confirmed: VaultAlbumId? = null
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album())),
                confirmingAlbumDeleteId = albumId,
            ),
            onAlbumDeleteConfirmed = { id -> confirmed = id },
        )

        rule.onNodeWithText(string(R.string.vault_album_delete_warning)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_delete_confirm)).performClick()

        assertEquals(albumId, confirmed)
    }

    // ------------------------------------------------------------------ an open album

    @Test
    fun an_open_album_shows_its_items_and_a_way_back() {
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album())),
                openAlbum = VaultAlbumDetailUi(
                    album = album(),
                    contents = VaultAlbumContentsUi.Resolved(items = listOf(item()), staleItemIds = emptyList()),
                    memberItemIds = setOf(memberId),
                ),
            ),
        )

        rule.onNodeWithText("holiday.jpg").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_back_action)).assertIsDisplayed()
    }

    @Test
    fun an_empty_album_says_it_is_empty_rather_than_broken() {
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album(memberCount = 0))),
                openAlbum = VaultAlbumDetailUi(
                    album = album(memberCount = 0),
                    contents = VaultAlbumContentsUi.Resolved(items = emptyList(), staleItemIds = emptyList()),
                    memberItemIds = emptySet(),
                ),
            ),
        )

        rule.onNodeWithText(string(R.string.vault_album_empty)).assertIsDisplayed()
    }

    @Test
    fun a_reference_the_vault_no_longer_lists_is_shown_and_kept() {
        var removed: VaultItemId? = null
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album(memberCount = 2, staleCount = 1))),
                openAlbum = VaultAlbumDetailUi(
                    album = album(memberCount = 2, staleCount = 1),
                    contents = VaultAlbumContentsUi.Resolved(
                        items = listOf(item()),
                        staleItemIds = listOf(staleId),
                    ),
                    memberItemIds = setOf(memberId, staleId),
                ),
            ),
            onAlbumItemRemoved = { id -> removed = id },
        )

        rule.onNodeWithText(string(R.string.vault_album_stale_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_stale_body)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_stale_forget_action)).performClick()

        assertEquals(staleId, removed)
    }

    @Test
    fun an_album_whose_list_cannot_be_read_says_so_and_invents_nothing() {
        val unreadable = VaultIndexUiState.Unreadable(VaultIndexUnreadable.MetadataDamaged)
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album(resolved = false, memberCount = 3))),
                index = unreadable,
                openAlbum = VaultAlbumDetailUi(
                    album = album(resolved = false, memberCount = 3),
                    contents = VaultAlbumContentsUi.Unresolved(index = unreadable),
                    memberItemIds = setOf(memberId),
                ),
            ),
        )

        rule.onNodeWithText(string(R.string.vault_album_unresolved)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_count_unresolved_format, 3)).assertIsDisplayed()
    }

    @Test
    fun a_member_is_shown_as_a_member_and_offered_removal() {
        var removed: VaultItemId? = null
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album())),
                openAlbum = VaultAlbumDetailUi(
                    album = album(),
                    contents = VaultAlbumContentsUi.Resolved(items = listOf(item()), staleItemIds = emptyList()),
                    memberItemIds = setOf(memberId),
                ),
                editingAlbumItems = true,
            ),
            onAlbumItemRemoved = { id -> removed = id },
        )

        rule.onNodeWithText(string(R.string.vault_album_in_album)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_remove_item_action)).performClick()

        assertEquals(memberId, removed)
    }

    @Test
    fun a_file_that_is_not_in_the_album_is_offered_to_it() {
        var added: VaultItemId? = null
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album())),
                openAlbum = VaultAlbumDetailUi(
                    album = album(),
                    contents = VaultAlbumContentsUi.Resolved(items = listOf(item()), staleItemIds = emptyList()),
                    memberItemIds = emptySet(),
                ),
                editingAlbumItems = true,
            ),
            onAlbumItemAdded = { id -> added = id },
        )

        rule.onNodeWithText(string(R.string.vault_album_manage_items_hint)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_album_add_item_action)).performClick()

        assertEquals(memberId, added)
    }

    // ------------------------------------------------------------------ searching and sorting

    @Test
    fun the_surface_says_which_collection_is_being_searched() {
        draw(
            state(
                organization = VaultOrganizationUiState.Albums(listOf(album())),
                search = VaultSearchUiState.NoMatches,
                searchQuery = "winter",
            ),
        )

        rule.onNodeWithText(string(R.string.vault_search_no_album_matches, "winter")).assertIsDisplayed()
    }

    @Test
    fun an_unreadable_record_is_not_drawn_as_no_matches() {
        draw(
            state(
                organization = VaultOrganizationUiState.Unreadable(
                    VaultOrganizationUnreadable.MetadataDamaged,
                ),
                search = VaultSearchUiState.CannotSearch,
                searchQuery = "winter",
            ),
        )

        rule.onNodeWithText(string(R.string.vault_search_cannot_search_albums)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_search_no_album_matches, "winter")).assertDoesNotExist()
    }

    @Test
    fun the_search_box_and_the_sort_controls_are_on_the_surface() {
        draw(state(organization = VaultOrganizationUiState.Empty))

        rule.onNodeWithText(string(R.string.vault_search_label), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_section_all_items)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_sort_by_name)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_sort_direction_descending)).assertIsDisplayed()
    }

    @Test
    fun sorting_reports_the_field_that_was_chosen() {
        var chosen: VaultSortField? = null
        draw(state(organization = VaultOrganizationUiState.Empty), onSortFieldSelected = { field -> chosen = field })

        rule.onNodeWithText(string(R.string.vault_sort_by_size)).performClick()

        assertEquals(VaultSortField.Size, chosen)
    }
}
