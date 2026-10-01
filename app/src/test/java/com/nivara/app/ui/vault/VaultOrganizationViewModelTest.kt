package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.FakeReadyVaultRepository
import com.nivara.app.data.vault.FakeVaultLocationStore
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.vault.VaultAlbum
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultOrganizationFailure
import com.nivara.app.domain.vault.VaultOrganizationState
import com.nivara.app.domain.vault.VaultOrganizationUnreadable
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSortDirection
import com.nivara.app.domain.vault.VaultSortField
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.testing.FakeVaultOrganizationRepository
import com.nivara.app.testing.FakeVaultTrashRepository
import com.nivara.app.testing.testAlbumId
import com.nivara.app.testing.testItem
import com.nivara.app.testing.testItemId
import com.nivara.app.testing.testSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local JVM tests for the vault screen's organisation surface.
 *
 * The screen decides almost nothing by itself: it filters and orders metadata that was already read,
 * asks the existing session before a change, and hands every change to the album record's repository.
 * What these tests check is exactly that division of labour — that typing or sorting never reads the
 * vault again, that a change without a session never reaches the repository, that a change is drawn as
 * committed only after the record was read back, and that an unreadable record is never drawn as a
 * vault without albums.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultOrganizationViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private var nowMillis: Long = 1_000L
    private val clock = TimeProvider { nowMillis }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ the list, the albums, the query

    @Test
    fun `the screen opens on the files, with the order it has always used`() = runTest {
        val model = viewModel(index = readableIndex(1, 2).second)

        val state = readyState(model)
        assertEquals(VaultSection.AllItems, state.section)
        assertEquals("", state.searchQuery)
        assertEquals(VaultSearchUiState.NotAsked, state.search)
        assertEquals(VaultSortField.ImportedAt, state.ordering.field)
        assertEquals(VaultSortDirection.Descending, state.ordering.direction)
        assertNull(state.openAlbum)
    }

    @Test
    fun `switching to the albums shows them and leaving again closes the open album`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip")))
        }
        val model = viewModel(albums = albums)

        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))
        assertEquals(VaultSection.Albums, readyState(model).section)
        assertNotNull(readyState(model).openAlbum)

        model.onSectionSelected(VaultSection.AllItems)
        assertNull("an album cannot stay open in another collection", readyState(model).openAlbum)
    }

    @Test
    fun `a search filters the list that was read, and reads nothing again`() = runTest {
        val (items, listOfFiles) = readableIndex(1, 2, 3)
        val model = viewModel(index = listOfFiles)
        val readsAfterOpen = listOfFiles.readCalls

        model.onSearchQueryChanged("file-2")

        val state = readyState(model)
        assertEquals(listOf("file-2.bin"), drawnNames(state))
        assertEquals(VaultSearchUiState.Matches, state.search)
        assertEquals(1, state.searchSummary?.matches)
        assertEquals(3, state.searchSummary?.total)
        assertEquals("filtering is not a read", readsAfterOpen, listOfFiles.readCalls)
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun `clearing the search brings the whole list back`() = runTest {
        val (_, listOfFiles) = readableIndex(1, 2)
        val model = viewModel(index = listOfFiles)

        model.onSearchQueryChanged("file-1")
        model.onSearchCleared()

        val state = readyState(model)
        assertEquals(2, drawnNames(state).size)
        assertEquals("", state.searchQuery)
        assertEquals(VaultSearchUiState.NotAsked, state.search)
        assertNull(state.searchSummary)
    }

    @Test
    fun `a search that matches nothing says so, and the list is still readable`() = runTest {
        val (_, listOfFiles) = readableIndex(1)
        val model = viewModel(index = listOfFiles)

        model.onSearchQueryChanged("nothing like this")

        val state = readyState(model)
        assertEquals(VaultSearchUiState.NoMatches, state.search)
        assertTrue(drawnNames(state).isEmpty())
        assertTrue("the list itself is still readable", state.index is VaultIndexUiState.Indexed)
    }

    @Test
    fun `a list that cannot be read cannot be searched, and is never drawn as no matches`() = runTest {
        val states = listOf(
            VaultIndexState.Unreadable(VaultIndexUnreadable.MetadataDamaged),
            VaultIndexState.Unavailable,
            VaultIndexState.AccessDenied,
            VaultIndexState.UnsupportedVersion(fileVersion = 2),
        )

        states.forEach { broken ->
            val model = viewModel(index = VaultIndexRepositoryDouble(broken))
            model.onSearchQueryChanged("holiday")

            val state = readyState(model)
            assertEquals("$broken", VaultSearchUiState.CannotSearch, state.search)
            assertNull("no count is invented for a list that was not read", state.searchSummary)
        }
    }

    @Test
    fun `an empty search over an empty vault is an answer, not a failure`() = runTest {
        val model = viewModel(index = VaultIndexRepositoryDouble(VaultIndexState.Missing))

        model.onSearchQueryChanged("holiday")

        assertEquals("the vault is known to hold nothing, which is an answer", VaultSearchUiState.NoMatches, readyState(model).search)
        assertNull(readyState(model).searchSummary)
    }

    // ------------------------------------------------------------------ sorting

    @Test
    fun `choosing a field re-orders the list in memory`() = runTest {
        val (_, listOfFiles) = readableIndex(1, 2, 3)
        val model = viewModel(index = listOfFiles)
        val readsAfterOpen = listOfFiles.readCalls

        model.onSortFieldSelected(VaultSortField.Name)

        val state = readyState(model)
        assertEquals(VaultSortField.Name, state.ordering.field)
        assertEquals("descending is the default direction", VaultSortDirection.Descending, state.ordering.direction)
        assertEquals(listOf("file-3.bin", "file-2.bin", "file-1.bin"), drawnNames(state))
        assertEquals("sorting is not a read", readsAfterOpen, listOfFiles.readCalls)
    }

    @Test
    fun `reversing the order keeps the field and reverses the list`() = runTest {
        val (_, listOfFiles) = readableIndex(1, 2)
        val model = viewModel(index = listOfFiles)
        val descending = drawnNames(readyState(model))

        model.onSortDirectionToggled()

        val state = readyState(model)
        assertEquals(VaultSortField.ImportedAt, state.ordering.field)
        assertEquals(VaultSortDirection.Ascending, state.ordering.direction)
        assertEquals("the same list, the other way round", descending.reversed(), drawnNames(state))
    }

    @Test
    fun `two files that look alike are ordered by their identifier, both ways`() = runTest {
        val model = viewModel(index = VaultIndexRepositoryDouble(VaultIndexState.Ready(items = sameLookingItems())))

        model.onSortFieldSelected(VaultSortField.Name)
        val descending = drawnNames(readyState(model))
        model.onSortDirectionToggled()
        val ascending = drawnNames(readyState(model))

        assertEquals(listOf("identical.bin", "identical.bin"), descending)
        assertEquals(
            "the same list, reversed rather than reshuffled",
            descending.reversed(),
            ascending,
        )
        assertEquals(
            "and the identifiers decide it",
            listOf(testItemId(1).value, testItemId(2).value),
            (readyState(model).index as VaultIndexUiState.Indexed).items.map { item -> item.id.value },
        )
    }

    // ------------------------------------------------------------------ creating and renaming

    @Test
    fun `creating an album hands the typed name to the record and shows what came back`() = runTest {
        val albums = FakeVaultOrganizationRepository()
        albums.onSuccess = { albums.state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip"))) }
        val model = viewModel(albums = albums, session = openSession())

        model.onCreateAlbum("Trip")

        assertEquals(listOf("create:Trip"), albums.calls)
        assertEquals(listOf(true), albums.authorizations)
        val state = readyState(model)
        assertEquals(listOf("Trip"), (state.organization as VaultOrganizationUiState.Albums).albums.map { it.name })
        assertEquals(R.string.vault_album_notice_created, state.noticeRes)
        assertNull(state.failure)
    }

    @Test
    fun `an album that could not be created is reported and never drawn as created`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            refuseWith(VaultOrganizationFailure.InvalidAlbumName)
        }
        val model = viewModel(albums = albums, session = openSession())

        model.onCreateAlbum("   ")

        val state = readyState(model)
        assertNull("nothing was created", state.noticeRes)
        assertEquals(R.string.vault_album_error_invalid_name, state.failure?.textRes)
        assertEquals(VaultOrganizationUiState.Empty, state.organization)
    }

    @Test
    fun `creating an album without a session asks for the gate and writes nothing`() = runTest {
        val albums = FakeVaultOrganizationRepository()
        val model = viewModel(albums = albums)

        model.onCreateAlbum("Trip")

        val state = readyState(model)
        assertTrue("the screen asks for the existing credential screen", state.unlockRequired)
        assertEquals(R.string.vault_locked, state.failure?.textRes)
        assertTrue("nothing reached the record", albums.calls.isEmpty())
    }

    @Test
    fun `a session that closes during a change is reported, and no success is claimed`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            mutationResultFactory = { NivaraResult.Failure(VaultOrganizationFailure.NotAuthorized) }
        }
        val model = viewModel(albums = albums, session = openSession())

        model.onCreateAlbum("Trip")

        val state = readyState(model)
        assertNull("an uncommitted change is not announced", state.noticeRes)
        assertTrue(state.unlockRequired)
        assertEquals(R.string.vault_album_error_not_authorized, state.failure?.textRes)
    }

    @Test
    fun `a repository that throws is a failed change, never a committed one`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            mutationResultFactory = { throw IllegalStateException("boom") }
        }
        val model = viewModel(albums = albums, session = openSession())

        model.onCreateAlbum("Trip")

        val state = readyState(model)
        assertNull(state.noticeRes)
        assertEquals(R.string.vault_album_error_failed, state.failure?.textRes)
        assertFalse(state.unlockRequired)
    }

    @Test
    fun `renaming asks for the new title, and abandoning it writes nothing`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Before")))
        }
        val model = viewModel(albums = albums, session = openSession())

        model.onRenameAlbumStarted(testAlbumId(1))
        assertEquals(testAlbumId(1), readyState(model).renamingAlbumId)

        model.onRenameAlbumCancelled()
        assertNull(readyState(model).renamingAlbumId)
        assertTrue("cancelling writes nothing", albums.calls.isEmpty())

        model.onRenameAlbumStarted(testAlbumId(1))
        model.onRenameAlbumConfirmed(testAlbumId(1), "After")

        assertEquals(listOf("rename:${testAlbumId(1).value}:After"), albums.calls)
        assertNull("the field is closed once the change is away", readyState(model).renamingAlbumId)
    }

    // ------------------------------------------------------------------ deleting an album

    @Test
    fun `deleting an album asks first, and only the confirmation deletes it`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip")))
        }
        val model = viewModel(albums = albums, session = openSession())

        model.onDeleteAlbumRequested(testAlbumId(1))
        assertEquals(testAlbumId(1), readyState(model).confirmingAlbumDeleteId)
        assertTrue("nothing is deleted by asking", albums.calls.isEmpty())

        model.onDeleteAlbumCancelled()
        assertNull(readyState(model).confirmingAlbumDeleteId)
        assertTrue(albums.calls.isEmpty())

        model.onDeleteAlbumRequested(testAlbumId(1))
        albums.onSuccess = { albums.state = VaultOrganizationState.Ready(emptyList()) }
        model.onDeleteAlbumConfirmed(testAlbumId(1))

        assertEquals(listOf("delete:${testAlbumId(1).value}"), albums.calls)
        assertEquals(R.string.vault_album_notice_deleted, readyState(model).noticeRes)
        assertEquals(VaultOrganizationUiState.Albums(emptyList()), readyState(model).organization)
    }

    @Test
    fun `deleting the album that is open closes it`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip")))
        }
        val model = viewModel(albums = albums, session = openSession())
        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))
        albums.onSuccess = { albums.state = VaultOrganizationState.Ready(emptyList()) }

        model.onDeleteAlbumConfirmed(testAlbumId(1))

        assertNull("nothing draws an album that is gone", readyState(model).openAlbum)
    }

    @Test
    fun `deleting an album without a session asks for the gate and deletes nothing`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip")))
        }
        val model = viewModel(albums = albums)

        model.onDeleteAlbumConfirmed(testAlbumId(1))

        assertTrue(readyState(model).unlockRequired)
        assertTrue(albums.calls.isEmpty())
        assertEquals(
            "the record still holds the album",
            VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip"))),
            albums.state,
        )
    }

    // ------------------------------------------------------------------ membership

    @Test
    fun `adding and removing membership goes to the record, never to the file`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip")))
        }
        val model = viewModel(albums = albums, session = openSession())
        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))

        model.onAddItemToAlbum(testItemId(7))
        model.onRemoveItemFromAlbum(testItemId(7))

        assertEquals(
            listOf("add:${testAlbumId(1).value}:${testItemId(7).value}", "remove:${testAlbumId(1).value}:${testItemId(7).value}"),
            albums.calls,
        )
        assertEquals(R.string.vault_album_notice_item_removed, readyState(model).noticeRes)
    }

    @Test
    fun `membership is decided by the record, so a filtered-out row is still a member`() = runTest {
        val members = listOf(testItemId(1), testItemId(2))
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip", members = members)))
        }
        val (_, listOfFiles) = readableIndex(1, 2)
        val model = viewModel(index = listOfFiles, albums = albums, session = openSession())
        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))

        // A search that hides one of the members from the list does not make it stop being a member.
        model.onSearchQueryChanged("file-2")

        val open = readyState(model).openAlbum!!
        assertEquals(setOf(testItemId(1), testItemId(2)), open.memberItemIds)
    }

    @Test
    fun `the open album's items are filtered and ordered like the vault's own list`() = runTest {
        val members = listOf(testItemId(1), testItemId(2))
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip", members = members)))
        }
        val (_, listOfFiles) = readableIndex(1, 2)
        val model = viewModel(index = listOfFiles, albums = albums, session = openSession())
        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))

        model.onSortFieldSelected(VaultSortField.Name)
        val descending = albumNames(readyState(model))
        model.onSearchQueryChanged("file-1")
        val filtered = albumNames(readyState(model))

        assertEquals(listOf("file-2.bin", "file-1.bin"), descending)
        assertEquals(listOf("file-1.bin"), filtered)
    }

    @Test
    fun `a reference the vault no longer lists is drawn as stale, and kept`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip", members = listOf(testItemId(1), testItemId(404)))))
        }
        val (_, listOfFiles) = readableIndex(1)
        val model = viewModel(index = listOfFiles, albums = albums)
        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))

        val open = readyState(model).openAlbum!!
        val contents = open.contents as VaultAlbumContentsUi.Resolved
        assertEquals(listOf("file-1.bin"), contents.items.map { item -> item.name })
        assertEquals(listOf(testItemId(404)), contents.staleItemIds)
        assertEquals("the count says how many are no longer in the vault", 1, open.album.staleCount)
        assertEquals("and nothing removed it on its own", 2, open.album.memberCount)
    }

    @Test
    fun `an album whose list cannot be read says so instead of showing nothing`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip", members = listOf(testItemId(1)))))
        }
        val model = viewModel(index = VaultIndexRepositoryDouble(VaultIndexState.Unreadable(VaultIndexUnreadable.MetadataDamaged)), albums = albums)
        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))

        val open = readyState(model).openAlbum!!
        assertTrue(open.contents is VaultAlbumContentsUi.Unresolved)
        val drawn = (readyState(model).organization as VaultOrganizationUiState.Albums).albums.single()
        assertFalse("the count is not claimed to be resolved", drawn.resolved)
    }

    @Test
    fun `a record that cannot be read is never drawn as a vault without albums`() = runTest {
        val states = listOf(
            VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.MetadataDamaged),
            VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.KeyUnavailable),
            VaultOrganizationState.UnsupportedVersion(fileVersion = 2),
            VaultOrganizationState.Unavailable,
            VaultOrganizationState.AccessDenied,
        )

        states.forEach { broken ->
            val model = viewModel(albums = FakeVaultOrganizationRepository().apply { state = broken })
            model.onSectionSelected(VaultSection.Albums)

            val drawn = readyState(model).organization
            assertFalse("$broken must not look like an empty album list", drawn is VaultOrganizationUiState.Empty)
            assertEquals("$broken", broken.toDrawnState(), drawn)
            assertFalse("and nothing may be written from it", drawn.acceptsChanges)
        }
    }

    @Test
    fun `a change is refused while the album record cannot be read, and nothing is asked of it`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.MetadataDamaged)
        }
        val model = viewModel(albums = albums, session = openSession())

        model.onCreateAlbum("Trip")

        assertEquals(R.string.vault_album_error_unavailable, readyState(model).failure?.textRes)
        assertTrue("the repository is never asked to change what cannot be read", albums.calls.isEmpty())
    }

    // ------------------------------------------------------------------ one viewer, three collections

    @Test
    fun `a file is the same row whichever collection it is found in`() = runTest {
        val members = listOf(testItemId(2))
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip", members = members)))
        }
        val (_, listOfFiles) = readableIndex(1, 2)
        val model = viewModel(index = listOfFiles, albums = albums, session = openSession())

        val fromTheList = (readyState(model).index as VaultIndexUiState.Indexed).items.single { item -> item.id == testItemId(2) }

        model.onSearchQueryChanged("file-2")
        val fromTheSearch = (readyState(model).index as VaultIndexUiState.Indexed).items.single()

        model.onSectionSelected(VaultSection.Albums)
        model.onAlbumOpened(testAlbumId(1))
        val fromTheAlbum = (readyState(model).openAlbum!!.contents as VaultAlbumContentsUi.Resolved).items.single()

        assertEquals("the same row, wherever it came from", fromTheList, fromTheSearch)
        assertEquals(fromTheList, fromTheAlbum)
    }

    // ------------------------------------------------------------------ the album surface's own search

    @Test
    fun `on the albums surface the query searches titles, and says which collection answered`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(
                listOf(album(seed = 1, name = "Summer Trip"), album(seed = 2, name = "Winter Trip")),
            )
        }
        val model = viewModel(albums = albums)
        model.onSectionSelected(VaultSection.Albums)

        model.onSearchQueryChanged("summer")

        val state = readyState(model)
        assertEquals(VaultSearchUiState.Matches, state.search)
        assertEquals(VaultSearchSummary(matches = 1, total = 2), state.searchSummary)
        assertEquals(
            listOf("Summer Trip"),
            (state.organization as VaultOrganizationUiState.Albums).albums.map { it.name },
        )
    }

    @Test
    fun `an album search that finds nothing is an answer about the titles`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip")))
        }
        val model = viewModel(albums = albums)
        model.onSectionSelected(VaultSection.Albums)

        model.onSearchQueryChanged("nothing")

        assertEquals(VaultSearchUiState.NoMatches, readyState(model).search)
    }

    @Test
    fun `an unreadable album record cannot be searched, and that is not an empty answer`() = runTest {
        val albums = FakeVaultOrganizationRepository().apply {
            state = VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.MetadataDamaged)
        }
        val model = viewModel(albums = albums)
        model.onSectionSelected(VaultSection.Albums)

        model.onSearchQueryChanged("trip")

        assertEquals(VaultSearchUiState.CannotSearch, readyState(model).search)
        assertNull(readyState(model).searchSummary)
    }

    // ------------------------------------------------------------------ messages

    @Test
    fun `a confirmation can be dismissed, and does not outlive the moment`() = runTest {
        val albums = FakeVaultOrganizationRepository()
        albums.onSuccess = { albums.state = VaultOrganizationState.Ready(listOf(album(seed = 1, name = "Trip"))) }
        val model = viewModel(albums = albums, session = openSession())

        model.onCreateAlbum("Trip")
        assertNotNull(readyState(model).noticeRes)

        model.onMessageShown()

        assertNull(readyState(model).noticeRes)
        assertNull(readyState(model).failure)
    }

    // ------------------------------------------------------------------ helpers

    /** What each unreadable record is drawn as, written out so the test cannot inherit the mapping. */
    private fun VaultOrganizationState.toDrawnState(): VaultOrganizationUiState = when (this) {
        is VaultOrganizationState.Unreadable -> VaultOrganizationUiState.Unreadable(reason)
        is VaultOrganizationState.UnsupportedVersion -> VaultOrganizationUiState.UnsupportedVersion
        VaultOrganizationState.Unavailable -> VaultOrganizationUiState.Unavailable
        VaultOrganizationState.AccessDenied -> VaultOrganizationUiState.AccessDenied
        else -> error("not part of this test: $this")
    }

    /** The real session manager with the test's clock, opened the way the credential screen opens it. */
    private fun openSession(): SessionManager =
        testSessionManager(clock).apply { establish(AuthenticationOutcome.Succeeded) }

    private fun viewModel(
        index: VaultIndexRepository = VaultIndexRepositoryDouble(VaultIndexState.Missing),
        albums: FakeVaultOrganizationRepository = FakeVaultOrganizationRepository(),
        trash: FakeVaultTrashRepository = FakeVaultTrashRepository(),
        repository: VaultRepository = FakeReadyVaultRepository(),
        session: SessionManager = testSessionManager(clock),
    ): VaultViewModel = VaultViewModel(
        vaultRepository = repository,
        indexRepository = index,
        organizationRepository = albums,
        trashRepository = trash,
        locationStore = FakeVaultLocationStore(),
        sessionManager = session,
    )

    private fun readyState(viewModel: VaultViewModel): VaultUiState.Ready =
        viewModel.uiState.value as? VaultUiState.Ready
            ?: error("the screen never left its loading state")

    private fun drawnNames(state: VaultUiState.Ready): List<String> =
        (state.index as? VaultIndexUiState.Indexed)?.items?.map { item -> item.name } ?: emptyList()

    private fun albumNames(state: VaultUiState.Ready): List<String> =
        (state.openAlbum?.contents as? VaultAlbumContentsUi.Resolved)?.items?.map { item -> item.name } ?: emptyList()

    private fun album(seed: Int, name: String, members: List<VaultItemId> = emptyList()): VaultAlbum =
        com.nivara.app.testing.testAlbum(seed = seed, name = name, itemIds = members)

    /** Items whose visible facts are identical, so only their identifiers can decide the order. */
    private fun sameLookingItems(): List<VaultItem> = listOf(
        testItem(seed = 1, name = "identical.bin", sizeBytes = 10L, importedAtEpochMillis = 5L),
        testItem(seed = 2, name = "identical.bin", sizeBytes = 10L, importedAtEpochMillis = 5L),
    )

    /** A readable list of files, newest first, with the repository that holds it. */
    private fun readableIndex(vararg seed: Int): Pair<List<VaultItem>, VaultIndexRepositoryDouble> {
        val items = seed.mapIndexed { position, value ->
            testItem(
                seed = value,
                name = "file-$value.bin",
                sizeBytes = 100L + position,
                importedAtEpochMillis = 1_000L - position,
            )
        }
        val repository = VaultIndexRepositoryDouble(VaultIndexState.Ready(items = items))
        return items to repository
    }
}

/** The vault's list of files, as a test controls it: this suite is not about importing. */
private class VaultIndexRepositoryDouble(
    private var state: VaultIndexState = VaultIndexState.Missing,
) : VaultIndexRepository {

    var readCalls: Int = 0
        private set

    override suspend fun read(): VaultIndexState {
        readCalls += 1
        return state
    }

    override suspend fun importFile(
        source: VaultSourceReference,
        authorize: () -> Boolean,
        onProgress: (VaultImportProgress) -> Unit,
    ): NivaraResult<VaultItem> = NivaraResult.Failure(VaultImportFailure.NotAuthorized)
}
