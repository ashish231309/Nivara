package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.FakeReadyVaultRepository
import com.nivara.app.data.vault.FakeVaultLocationStore
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultOrganizationState
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultTrashFailure
import com.nivara.app.domain.vault.VaultTrashSortField
import com.nivara.app.domain.vault.VaultTrashState
import com.nivara.app.domain.vault.VaultTrashUnreadable
import com.nivara.app.testing.FakeVaultOrganizationRepository
import com.nivara.app.testing.FakeVaultTrashRepository
import com.nivara.app.testing.testAlbum
import com.nivara.app.testing.testAlbumId
import com.nivara.app.testing.testItem
import com.nivara.app.testing.testItemId
import com.nivara.app.testing.testSessionManager
import com.nivara.app.testing.testTrashEntry
import com.nivara.app.testing.FakeVaultRecoveryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local JVM tests for the trash surface's state machine.
 *
 * The screen is the last place a wrong sentence can be said, so what is held here is that the active
 * collection really excludes what the trash record names, that a record which cannot be read is never
 * drawn as an empty trash, that every change goes through the existing gate, and that a change which
 * comes back refused leaves the screen showing what is actually committed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultTrashViewModelTest {

    private var now: Long = 1_700_000_000_000L
    private val clock = TimeProvider { now }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FixedIndexRepository(
        private val state: () -> VaultIndexState,
    ) : VaultIndexRepository {

        override suspend fun read(): VaultIndexState = state()

        override suspend fun importFile(
            source: VaultSourceReference,
            authorize: () -> Boolean,
            onProgress: (VaultImportProgress) -> Unit,
        ): NivaraResult<VaultItem> = NivaraResult.Failure(VaultFailure.StorageUnavailable)
    }

    private fun openSession(): SessionManager =
        testSessionManager(clock).apply { establish(AuthenticationOutcome.Succeeded) }

    private fun viewModel(
        index: VaultIndexState = VaultIndexState.Ready(items = emptyList()),
        albums: FakeVaultOrganizationRepository = FakeVaultOrganizationRepository(),
        trash: FakeVaultTrashRepository = FakeVaultTrashRepository(),
        repository: VaultRepository = FakeReadyVaultRepository(),
        session: SessionManager = testSessionManager(clock),
    ): VaultViewModel = VaultViewModel(
        vaultRepository = repository,
        indexRepository = FixedIndexRepository { index },
        organizationRepository = albums,
        trashRepository = trash,
        recoveryRepository = FakeVaultRecoveryRepository(),
        locationStore = FakeVaultLocationStore(),
        sessionManager = session,
    )

    private fun readyState(viewModel: VaultViewModel): VaultUiState.Ready =
        viewModel.uiState.value as? VaultUiState.Ready
            ?: error("the screen never left its loading state")

    private fun drawnNames(state: VaultUiState.Ready): List<String> =
        (state.index as? VaultIndexUiState.Indexed)?.items?.map { item -> item.name } ?: emptyList()

    // ------------------------------------------------------------------ the active collection

    @Test
    fun the_active_list_excludes_the_items_the_trash_record_names() = runTest {
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1), testItem(seed = 2))),
            trash = FakeVaultTrashRepository(
                VaultTrashState.Ready(listOf(testTrashEntry(seed = 1))),
            ),
        )
        assertEquals(listOf("file-2.bin"), drawnNames(readyState(viewModel)))
    }

    @Test
    fun a_record_that_cannot_be_read_is_not_drawn_as_an_empty_trash() = runTest {
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            trash = FakeVaultTrashRepository(
                VaultTrashState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
            ),
        )
        val state = readyState(viewModel)
        assertEquals(
            VaultTrashUiState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
            state.trash,
        )
        assertTrue(state.trashStateUnknown)
        assertEquals(listOf("file-1.bin"), drawnNames(state))
    }

    @Test
    fun a_missing_record_leaves_the_active_list_whole_and_says_nothing_is_trashed() = runTest {
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1), testItem(seed = 2))),
        )
        val state = readyState(viewModel)
        assertEquals(VaultTrashUiState.Empty, state.trash)
        assertFalse(state.trashStateUnknown)
        assertEquals(listOf("file-1.bin", "file-2.bin"), drawnNames(state).sorted())
    }

    // ------------------------------------------------------------------ changing the trash

    @Test
    fun moving_an_item_to_trash_asks_the_repository_and_reports_it() = runTest {
        val trash = FakeVaultTrashRepository(VaultTrashState.Missing).apply {
            onSuccess = { report(VaultTrashState.Ready(listOf(testTrashEntry(seed = 1)))) }
        }
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1), testItem(seed = 2))),
            trash = trash,
            session = openSession(),
        )
        viewModel.onTrashItemRequested(testItemId(1))
        val state = readyState(viewModel)
        assertEquals(listOf("trash:${testItemId(1).value}"), trash.calls)
        assertEquals(R.string.vault_trash_notice_trashed, state.noticeRes)
        assertNull(state.failure)
        assertEquals(listOf("file-2.bin"), drawnNames(state))
    }

    @Test
    fun restoring_an_item_asks_the_repository_and_reports_it() = runTest {
        val trash = FakeVaultTrashRepository(VaultTrashState.Ready(listOf(testTrashEntry(seed = 1)))).apply {
            onSuccess = { report(VaultTrashState.Ready(emptyList())) }
        }
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            trash = trash,
            session = openSession(),
        )
        viewModel.onRestoreRequested(testItemId(1))
        val state = readyState(viewModel)
        assertEquals(listOf("restore:${testItemId(1).value}"), trash.calls)
        assertEquals(R.string.vault_trash_notice_restored, state.noticeRes)
        assertEquals(VaultTrashUiState.Trashed(emptyList()), state.trash)
        assertEquals(listOf("file-1.bin"), drawnNames(state))
    }

    @Test
    fun a_change_without_a_session_asks_for_the_existing_gate_and_changes_nothing() = runTest {
        val trash = FakeVaultTrashRepository(VaultTrashState.Missing)
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            trash = trash,
        )
        viewModel.onTrashItemRequested(testItemId(1))
        val state = readyState(viewModel)
        assertTrue(state.unlockRequired)
        assertTrue(trash.calls.isEmpty())
        assertTrue(trash.authorizations.isEmpty())
    }

    @Test
    fun a_record_that_cannot_be_read_refuses_a_change_rather_than_writing_over_it() = runTest {
        val trash = FakeVaultTrashRepository(
            VaultTrashState.Unreadable(VaultTrashUnreadable.KeyUnavailable),
        )
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            trash = trash,
            session = openSession(),
        )
        viewModel.onRestoreRequested(testItemId(1))
        val state = readyState(viewModel)
        assertEquals(vaultTrashUnavailableMessage(), state.failure)
        assertTrue(trash.calls.isEmpty())
    }

    @Test
    fun a_refused_change_is_reported_and_the_record_is_read_again() = runTest {
        val trash = FakeVaultTrashRepository(VaultTrashState.Missing).apply {
            refuseWith(VaultTrashFailure.WriteFailed)
        }
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            trash = trash,
            session = openSession(),
        )
        val readsBefore = trash.readCalls
        viewModel.onTrashItemRequested(testItemId(1))
        val state = readyState(viewModel)
        assertEquals(VaultTrashFailure.WriteFailed.asMessage(), state.failure)
        assertEquals(readsBefore + 1, trash.readCalls)
    }

    // ------------------------------------------------------------------ search and sort

    @Test
    fun the_trash_surface_searches_only_what_the_trash_record_names() = runTest {
        val viewModel = viewModel(
            index = VaultIndexState.Ready(
                items = listOf(testItem(seed = 1, name = "trip.jpg"), testItem(seed = 2, name = "invoice.pdf")),
            ),
            trash = FakeVaultTrashRepository(
                VaultTrashState.Ready(listOf(testTrashEntry(seed = 2))),
            ),
        )
        viewModel.onSectionSelected(VaultSection.Trash)
        viewModel.onSearchQueryChanged("invoice")
        val matched = readyState(viewModel)
        assertEquals(VaultSearchUiState.Matches, matched.search)
        assertEquals(1, matched.searchSummary?.matches)

        viewModel.onSearchQueryChanged("trip")
        val excluded = readyState(viewModel)
        assertEquals(VaultSearchUiState.NoMatches, excluded.search)
        assertEquals(0, excluded.searchSummary?.matches)
    }

    @Test
    fun a_record_that_cannot_be_read_cannot_be_searched_and_says_so() = runTest {
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            trash = FakeVaultTrashRepository(VaultTrashState.Unavailable),
        )
        viewModel.onSectionSelected(VaultSection.Trash)
        viewModel.onSearchQueryChanged("file")
        assertEquals(VaultSearchUiState.CannotSearch, readyState(viewModel).search)
    }

    @Test
    fun sorting_the_trash_does_not_read_the_vault_again() = runTest {
        val trash = FakeVaultTrashRepository(VaultTrashState.Ready(listOf(testTrashEntry(seed = 1))))
        val viewModel = viewModel(trash = trash)
        val readsBefore = trash.readCalls
        viewModel.onTrashSortFieldSelected(VaultTrashSortField.Name)
        viewModel.onTrashSortDirectionToggled()
        assertEquals(readsBefore, trash.readCalls)
        assertEquals(VaultTrashSortField.Name, readyState(viewModel).trashOrdering.field)
    }

    // ------------------------------------------------------------------ albums

    @Test
    fun an_album_keeps_naming_a_member_that_is_in_the_trash() = runTest {
        val albums = FakeVaultOrganizationRepository(
            VaultOrganizationState.Ready(
                listOf(testAlbum(seed = 1, itemIds = listOf(testItemId(1)))),
            ),
        )
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            albums = albums,
            trash = FakeVaultTrashRepository(VaultTrashState.Ready(listOf(testTrashEntry(seed = 1)))),
        )
        val state = readyState(viewModel)
        val album = (state.organization as VaultOrganizationUiState.Albums).albums.single()
        assertEquals(1, album.memberCount)
        assertEquals(1, album.trashedCount)
        assertEquals(0, album.staleCount)
    }

    @Test
    fun an_open_album_does_not_draw_a_trashed_member_as_active_content() = runTest {
        val albums = FakeVaultOrganizationRepository(
            VaultOrganizationState.Ready(
                listOf(testAlbum(seed = 1, itemIds = listOf(testItemId(1), testItemId(2)))),
            ),
        )
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1), testItem(seed = 2))),
            albums = albums,
            trash = FakeVaultTrashRepository(VaultTrashState.Ready(listOf(testTrashEntry(seed = 1)))),
        )
        viewModel.onAlbumOpened(testAlbumId(1))
        val contents = readyState(viewModel).openAlbum?.contents as VaultAlbumContentsUi.Resolved
        assertEquals(listOf("file-2.bin"), contents.items.map { item -> item.name })
        assertEquals(listOf(testItemId(1)), contents.trashedItemIds)
    }

    @Test
    fun an_unreadable_record_does_not_remove_anything_from_the_albums_or_the_list() = runTest {
        val albums = FakeVaultOrganizationRepository(
            VaultOrganizationState.Ready(
                listOf(testAlbum(seed = 1, itemIds = listOf(testItemId(1)))),
            ),
        )
        val viewModel = viewModel(
            index = VaultIndexState.Ready(items = listOf(testItem(seed = 1))),
            albums = albums,
            trash = FakeVaultTrashRepository(
                VaultTrashState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
            ),
        )
        val state = readyState(viewModel)
        assertEquals(listOf("file-1.bin"), drawnNames(state))
        val album = (state.organization as VaultOrganizationUiState.Albums).albums.single()
        assertEquals(1, album.memberCount)
        assertEquals(0, album.trashedCount)
        assertTrue(state.trashStateUnknown)
    }
}
