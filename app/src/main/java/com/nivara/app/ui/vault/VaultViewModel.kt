package com.nivara.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isSuccess
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.vault.VaultAlbumContents
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationStore
import com.nivara.app.domain.vault.VaultOrdering
import com.nivara.app.domain.vault.VaultOrganizationFailure
import com.nivara.app.domain.vault.VaultOrganizationRepository
import com.nivara.app.domain.vault.VaultOrganizationState
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSearch
import com.nivara.app.domain.vault.VaultSearchQuery
import com.nivara.app.domain.vault.VaultSearchResult
import com.nivara.app.domain.vault.VaultSortField
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.asOrganizationFailure
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.inOrder
import com.nivara.app.domain.vault.resolveAgainst
import com.nivara.app.domain.vault.search
import com.nivara.app.ui.components.NivaraMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The vault screen's state machine.
 *
 * ### What it is responsible for
 *
 * Showing what is at the root the user pointed Nivara at, adopting a newly selected folder, and
 * creating a vault when the user asks for one. It reads the vault's state from the repository after
 * every action rather than assuming an outcome, so what is drawn is what the storage says.
 *
 * ### What it deliberately does not do
 *
 * It does not authenticate anybody: it asks the existing [SessionManager] whether the gate is open and
 * refuses a change when it is not, and the credential screen that opens the gate is the one that
 * already exists. It does not touch the vault key, the wrapped key or any envelope — the repository
 * owns all of that, and nothing that could open the vault ever reaches a screen state. It does not
 * know about the hidden-application set, the protected set, App Lock or the application's identity:
 * the vault is a storage foundation, and none of those features is involved in it.
 *
 * ### Why creating a vault needs a session
 *
 * Creating a vault is a durable configuration change on storage the user chose, and it is the step
 * that generates the key material everything later depends on. It therefore requires the same session
 * every other configuration change in Nivara requires — the same gate, the same credential screen,
 * and no second password of the vault's own. Looking at the screen does not require one: it says where
 * the vault is and whether it can be opened, which is not personal data about its contents.
 *
 * ### The storage reference
 *
 * The platform hands the screen an opaque reference for the folder the user picked, and the screen
 * passes it through unchanged. Nothing here parses it, displays it or stores it: the location store
 * owns it, and the vault is identified by what the metadata says, never by where it sits.
 */
class VaultViewModel(
    private val vaultRepository: VaultRepository,
    private val indexRepository: VaultIndexRepository,
    private val organizationRepository: VaultOrganizationRepository,
    private val locationStore: VaultLocationStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<VaultUiState>(VaultUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<VaultUiState> = mutableUiState.asStateFlow()

    private var vaultState: VaultState? = null

    /**
     * The index as the domain reported it, kept beside the drawn list.
     *
     * The screen's list is derived from this — filtered by the search box, ordered by the chosen sort
     * — so a keystroke in the search box or a tap on a sort control never reads the vault again. It
     * is also what resolves an album's references into files.
     */
    private var domainIndex: VaultIndexState? = null
    private var indexState: VaultIndexUiState = VaultIndexUiState.Loading
    private var domainOrganization: VaultOrganizationState? = null
    private var ordering: VaultOrdering = VaultOrdering()
    private var query: VaultSearchQuery = VaultSearchQuery.NONE
    private var queryText: String = ""
    private var section: VaultSection = VaultSection.AllItems
    private var openAlbumId: VaultAlbumId? = null
    private var renamingAlbumId: VaultAlbumId? = null
    private var confirmingDeleteId: VaultAlbumId? = null
    private var editingAlbumItems: Boolean = false
    private var busy: Boolean = false
    private var importing: Boolean = false
    private var progress: VaultImportProgress? = null
    private var publishedProgressBytes: Long = 0L
    private var unlockRequired: Boolean = false
    private var failure: NivaraMessage? = null
    private var noticeRes: Int? = null
    private var pendingLocation: VaultLocation? = null

    init {
        inspect(showLoading = true)
        // The gate closes on its own — a timeout, or Quick Lock from anywhere in the application — and
        // it is the authority on whether a change may be made. Watching it keeps the controls honest
        // without the screen deciding for itself.
        viewModelScope.launch {
            sessionManager.state.collect { publish() }
        }
    }

    /** Re-reads the root, showing the loading state. */
    fun refresh() {
        inspect(showLoading = true)
    }

    /**
     * Re-reads the root when the screen comes back to the foreground.
     *
     * This is how a folder that was removed, a grant that was revoked or a vault that was created by
     * another process is noticed: the state is never assumed, it is read again.
     */
    fun onResumed() {
        inspect(showLoading = false)
    }

    /**
     * Adopts the folder the user picked.
     *
     * The reference comes straight from the platform's folder picker and is handed to the location
     * store, which takes the durable permission and stores it. A selection that cannot be adopted
     * leaves the previous selection untouched, and the screen says so rather than pretending a new
     * root was configured.
     */
    fun onRootSelected(reference: String) {
        val current = readyState() ?: return
        if (current.busy) return

        // A selection Nivara cannot use as a location is refused here, before anything is remembered:
        // it is reported as a failed selection and the previous root is left exactly as it was.
        val location = VaultLocation.create(reference)
        if (location == null) {
            unlockRequired = false
            noticeRes = null
            failure = vaultSelectionFailedMessage()
            publish()
            return
        }

        if (!hasSession()) {
            // Re-selecting a root changes which storage Nivara uses, so it is a configuration change
            // like any other. The reference is kept so the user's pick is not lost while they unlock.
            pendingLocation = location
            return
        }

        adopt(location)
    }

    /** Creates a vault at the selected root. */
    fun initialize() {
        val current = readyState() ?: return
        if (current.busy || !current.vaultCanBeInitialized) return
        if (!hasSession()) return
        run(replaceUnreadable = false)
    }

    /**
     * Replaces records at the selected root that Nivara could not open.
     *
     * The only destructive action in the stage. The screen offers it only for an unreadable vault and
     * says what it costs; the repository refuses it for a valid vault and for one written by a newer
     * Nivara, where the records are readable and merely unknown.
     */
    fun replaceUnreadable() {
        val current = readyState() ?: return
        if (current.busy || !current.vaultHasUnreadableRecords) return
        if (!hasSession()) return
        run(replaceUnreadable = true)
    }

    /**
     * Imports the document the user picked.
     *
     * The reference arrives from the platform's own document picker and is used for this import only:
     * it is not stored, and the vault never depends on it again — the imported file must keep working
     * after the original has been moved, renamed or deleted.
     *
     * Nothing is imported while the gate is closed. The user is sent to the existing credential
     * screen, and the selection is *dropped* rather than remembered: an import writes an encrypted
     * copy of a file the user chose, and starting it later — after some other unlock — would be a
     * change they did not ask for at that moment. They pick the file again, deliberately.
     */
    fun onFileSelected(reference: String) {
        val current = readyState() ?: return
        if (current.busy || current.importing) return

        val source = VaultSourceReference.create(reference)
        if (source == null) {
            unlockRequired = false
            noticeRes = null
            failure = vaultImportSelectionFailedMessage()
            publish()
            return
        }

        if (!hasSession()) {
            // `hasSession` has already asked for the credential screen and explained why; the file
            // itself is not kept.
            failure = vaultImportLockedMessage()
            publish()
            return
        }

        startImport(source)
    }

    private fun startImport(source: VaultSourceReference) {
        importing = true
        busy = true
        progress = null
        publishedProgressBytes = 0L
        failure = null
        noticeRes = null
        publish()

        viewModelScope.launch {
            val result = try {
                indexRepository.importFile(
                    source = source,
                    // Asked again by the pipeline at the moment a durable change would be made, so a
                    // session that closes during a long encryption stops the import instead of being
                    // extended by it. Nothing here refreshes or lengthens the session.
                    authorize = { sessionManager.currentState().isAuthenticated },
                    onProgress = { update -> onImportProgress(update) },
                )
            } catch (cancellation: CancellationException) {
                // The screen is gone or the work was cancelled: the pipeline removes the unfinished
                // object it wrote, and no item was ever added to the index.
                throw cancellation
            } catch (error: Exception) {
                // The contract reports failures as results. An implementation that throws anyway is
                // reported as a failed import, never as an imported file.
                null
            }

            importing = false
            busy = false
            progress = null
            when {
                result == null -> failure = vaultImportFailedMessage()

                result is NivaraResult.Failure -> {
                    val typed = result.error as? VaultImportFailure
                    if (typed == VaultImportFailure.NotAuthorized) {
                        // The session closed while the file was being encrypted. The import was
                        // abandoned and its unfinished object removed; the user is asked to unlock
                        // and to pick the file again.
                        unlockRequired = true
                    }
                    failure = typed?.asMessage() ?: vaultImportFailedMessage()
                }

                else -> noticeRes = R.string.vault_notice_imported
            }
            // The vault and its list are read again rather than assumed: the screen shows what the
            // storage says, and a committed import appears because the index names it.
            inspect(showLoading = false, keepMessages = true)
        }
    }

    /**
     * Reports how far the encryption has come.
     *
     * Progress is a display concern and nothing else: it changes no byte that is written and no
     * record that is authenticated. The state is republished only when the reported position has
     * moved far enough to change what a person sees, so a large file does not redraw the screen
     * thousands of times for the same percentage.
     */
    private fun onImportProgress(update: VaultImportProgress) {
        progress = update
        if (update.bytesProcessed - publishedProgressBytes >= PROGRESS_PUBLISH_BYTES ||
            update.bytesProcessed == 0L
        ) {
            publishedProgressBytes = update.bytesProcessed
            publish()
        }
    }

    /**
     * The state the screen is in, or `null` while the first read has not finished.
     *
     * Actions are only meaningful once there is a state to act from; a tap during the first read is
     * dropped rather than acted on against a vault that has not been read yet.
     */
    private fun readyState(): VaultUiState.Ready? = mutableUiState.value as? VaultUiState.Ready

    /**
     * Whether the existing gate currently authorizes a change.
     *
     * Asked at the moment of the action, not from the drawn state, so a session that expired between
     * the render and the tap cannot let a change through.
     */
    private fun hasSession(): Boolean {
        if (sessionManager.currentState().isAuthenticated) return true
        unlockRequired = true
        failure = vaultLockedMessage()
        noticeRes = null
        publish()
        return false
    }

    /**
     * Called once the screen has sent the user to the credential screen and come back.
     *
     * A folder the user picked before unlocking is adopted now that the gate is open, so the pick is
     * not silently discarded. It is adopted **only** if the gate actually opened: coming back without
     * authenticating applies nothing — and drops the pick rather than keeping it, because a selection
     * that would be applied at some later unlock is a change the user did not ask for at that moment.
     * Creating a vault is never retried either; that is always an explicit tap.
     */
    fun onUnlockHandled() {
        if (!unlockRequired) return
        unlockRequired = false
        val pending = pendingLocation
        pendingLocation = null
        publish()
        if (pending == null || !sessionManager.currentState().isAuthenticated) return
        adopt(pending)
    }

    // ------------------------------------------------------------------ browsing and organising

    /** Switches between every file and the albums. */
    fun onSectionSelected(section: VaultSection) {
        if (this.section == section) return
        this.section = section
        // Leaving the albums closes whatever album was open: the section is the collection being
        // shown, and an album card inside the file list would be a second screen's worth of state
        // surviving a navigation the user made.
        if (section == VaultSection.AllItems) {
            openAlbumId = null
            editingAlbumItems = false
            renamingAlbumId = null
            confirmingDeleteId = null
        }
        publish()
    }

    /**
     * The search box changed.
     *
     * Nothing is read and nothing is written: the query filters metadata the screen already has. The
     * text is kept exactly as typed so the field draws what the person is typing, while the filter
     * uses the normalised form.
     */
    fun onSearchQueryChanged(text: String) {
        queryText = text
        query = VaultSearchQuery.of(text)
        publish()
    }

    /** Clears the search box. */
    fun onSearchCleared() {
        if (queryText.isEmpty() && query.isBlank) return
        onSearchQueryChanged("")
    }

    /** Chooses the field the list is ordered by. The direction is kept. */
    fun onSortFieldSelected(field: VaultSortField) {
        if (ordering.field == field) return
        ordering = ordering.copy(field = field)
        publish()
    }

    /** Reverses the current order. */
    fun onSortDirectionToggled() {
        ordering = ordering.toggled()
        publish()
    }

    /** Opens an album: its items are resolved from the index that was already read. */
    fun onAlbumOpened(albumId: VaultAlbumId) {
        openAlbumId = albumId
        editingAlbumItems = false
        renamingAlbumId = null
        confirmingDeleteId = null
        publish()
    }

    /** Leaves the open album. Nothing is read and nothing is written. */
    fun onAlbumClosed() {
        if (openAlbumId == null) return
        openAlbumId = null
        editingAlbumItems = false
        renamingAlbumId = null
        confirmingDeleteId = null
        publish()
    }

    /** Shows the field for renaming [albumId]. */
    fun onRenameAlbumStarted(albumId: VaultAlbumId) {
        renamingAlbumId = albumId
        confirmingDeleteId = null
        publish()
    }

    /** Abandons a rename without writing anything. */
    fun onRenameAlbumCancelled() {
        if (renamingAlbumId == null) return
        renamingAlbumId = null
        publish()
    }

    /** Asks to delete [albumId]. The confirmation is drawn by the screen; nothing is deleted yet. */
    fun onDeleteAlbumRequested(albumId: VaultAlbumId) {
        confirmingDeleteId = albumId
        renamingAlbumId = null
        publish()
    }

    /** Abandons a deletion. */
    fun onDeleteAlbumCancelled() {
        if (confirmingDeleteId == null) return
        confirmingDeleteId = null
        publish()
    }

    /** Shows or hides the open album's add/remove surface. */
    fun onAlbumItemsEditingChanged(editing: Boolean) {
        if (editingAlbumItems == editing) return
        editingAlbumItems = editing
        publish()
    }

    /**
     * Creates an album titled [name].
     *
     * Everything an album change requires: a vault that can be opened, a readable album record, and
     * the existing session. The repository asks the same question again at the moment the record
     * would change, so a session that ends while the screen is drawing cannot produce a change.
     */
    fun onCreateAlbum(name: String) {
        if (!canChangeAlbums()) return
        changeAlbum(
            work = { authorize -> organizationRepository.createAlbum(name = name, authorize = authorize) },
            onDone = { R.string.vault_album_notice_created },
        )
    }

    /** Renames the album [albumId] to [name]. */
    fun onRenameAlbumConfirmed(albumId: VaultAlbumId, name: String) {
        if (!canChangeAlbums()) return
        renamingAlbumId = null
        changeAlbum(
            work = { authorize ->
                organizationRepository.renameAlbum(albumId = albumId, name = name, authorize = authorize)
            },
            onDone = { R.string.vault_album_notice_renamed },
        )
    }

    /**
     * Deletes the album [albumId] — the album, and nothing else.
     *
     * The items it named are not touched: they stay in the vault, in the index and in every other
     * album they are in. The confirmation the screen draws before this says so, because a person
     * deleting a list should not have to wonder what else went with it.
     */
    fun onDeleteAlbumConfirmed(albumId: VaultAlbumId) {
        if (!canChangeAlbums()) return
        confirmingDeleteId = null
        changeAlbum(
            work = { authorize -> organizationRepository.deleteAlbum(albumId = albumId, authorize = authorize) },
            onDone = { R.string.vault_album_notice_deleted },
            closesAlbum = albumId,
        )
    }

    /** Adds [itemId] to the open album. */
    fun onAddItemToAlbum(itemId: VaultItemId) {
        val albumId = openAlbumId ?: return
        if (!canChangeAlbums()) return
        changeAlbum(
            work = { authorize ->
                organizationRepository.addItem(albumId = albumId, itemId = itemId, authorize = authorize)
            },
            onDone = { R.string.vault_album_notice_item_added },
        )
    }

    /** Removes [itemId] from the open album. The item itself is untouched. */
    fun onRemoveItemFromAlbum(itemId: VaultItemId) {
        val albumId = openAlbumId ?: return
        if (!canChangeAlbums()) return
        changeAlbum(
            work = { authorize ->
                organizationRepository.removeItem(albumId = albumId, itemId = itemId, authorize = authorize)
            },
            onDone = { R.string.vault_album_notice_item_removed },
        )
    }

    /**
     * Whether an album may be changed right now.
     *
     * Three things, asked at the moment of the tap rather than read from the drawn state: the vault is
     * open, its album record can be read (or does not exist yet), and the existing gate is open. A
     * change refused here is explained the same way every other refused change in this screen is.
     */
    private fun canChangeAlbums(): Boolean {
        val current = readyState() ?: return false
        if (current.busy || current.importing) return false
        val vault = current.vault
        if (vault !is VaultState.Ready || !current.organization.acceptsChanges) {
            failure = vaultOrganizationUnavailableMessage()
            noticeRes = null
            publish()
            return false
        }
        return hasSession()
    }

    /**
     * Runs one album change, then reads the record back.
     *
     * The albums are read again from storage after every change rather than patched in memory: an
     * album list assembled by the screen would be a second opinion about what the vault holds, and the
     * one thing this layer must never do is claim a change that was not committed.
     */
    private fun changeAlbum(
        work: suspend (authorize: () -> Boolean) -> NivaraResult<*>,
        onDone: () -> Int,
        closesAlbum: VaultAlbumId? = null,
    ) {
        busy = true
        failure = null
        noticeRes = null
        publish()

        viewModelScope.launch {
            val result = try {
                work { sessionManager.currentState().isAuthenticated }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The contract reports failures as results. An implementation that throws anyway is
                // reported as a failed change, never as a changed album.
                null
            }

            busy = false
            when {
                result == null -> failure = vaultAlbumChangeFailedMessage()

                result is NivaraResult.Failure -> {
                    // How a thrown failure becomes a typed one is the domain's own rule, not the
                    // screen's: the same reading is used everywhere a result is turned into a message.
                    val typed = result.error.asOrganizationFailure()
                    if (typed == VaultOrganizationFailure.NotAuthorized) unlockRequired = true
                    failure = typed?.asMessage() ?: vaultAlbumChangeFailedMessage()
                    noticeRes = null
                }

                else -> {
                    failure = null
                    noticeRes = onDone()
                }
            }
            if (closesAlbum != null && result != null && result.isSuccess) {
                openAlbumId = null
                editingAlbumItems = false
            }
            // The record is read again whatever happened, so a change that did not verify is drawn as
            // the albums that are actually there.
            readOrganization()
            publish()
        }
    }

    /** Clears the last failure or confirmation, so a message does not outlive the moment. */
    fun onMessageShown() {
        if (failure == null && noticeRes == null) return
        failure = null
        noticeRes = null
        publish()
    }

    private fun adopt(location: VaultLocation) {
        busy = true
        failure = null
        noticeRes = null
        publish()

        viewModelScope.launch {
            val stored = try {
                locationStore.storeLocation(location)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                null
            }

            busy = false
            val adopted = stored != null && stored.isSuccess
            failure = if (adopted) null else vaultSelectionFailedMessage()
            noticeRes = if (adopted) R.string.vault_notice_root_selected else null
            // Whatever happened, the screen is rebuilt from the storage: an adopted root shows the
            // vault that is there, and a refused one shows the previous root's state unchanged.
            inspect(showLoading = false, keepMessages = true)
        }
    }

    private fun run(replaceUnreadable: Boolean) {
        busy = true
        failure = null
        noticeRes = null
        publish()

        viewModelScope.launch {
            val result = try {
                vaultRepository.initialize(replaceUnreadable = replaceUnreadable)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The repository reports failures instead of throwing; a contract violation must still
                // not take the screen down, and must not be reported as a created vault.
                null
            }

            busy = false
            val created = result != null && result.isSuccess
            failure = if (created) {
                null
            } else {
                (result as? NivaraResult.Failure)?.let { failure -> failure.error }?.asMessage()
                    ?: vaultSelectionFailedMessage()
            }
            noticeRes = if (created) R.string.vault_notice_initialized else null
            // The vault is read back from the storage rather than assumed: a record that did not
            // validate becomes a failure, not a screen claiming a vault exists.
            inspect(showLoading = false, keepMessages = true)
        }
    }

    private fun inspect(showLoading: Boolean, keepMessages: Boolean = false) {
        viewModelScope.launch {
            if (showLoading) {
                mutableUiState.value = VaultUiState.Loading
            }
            val state = try {
                vaultRepository.inspect()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The contract reports failures as states. If an implementation throws anyway, the
                // screen says the root cannot be reached rather than showing an empty vault.
                VaultState.Unavailable
            }
            vaultState = state
            // The list of files is a second fact, read only when the vault itself can be opened. An
            // index that cannot be read becomes its own state, never an empty list.
            if (state is VaultState.Ready) {
                indexState = readIndex()
                // The albums are a third fact, read from their own record. Like the index they are
                // never rebuilt, and a record that cannot be read stays a record that cannot be read.
                readOrganization()
            } else {
                domainIndex = VaultIndexState.VaultNotReady(state)
                indexState = drawnIndex()
                domainOrganization = VaultOrganizationState.VaultNotReady(state)
            }
            if (!keepMessages) {
                failure = null
                noticeRes = null
            }
            publish()
        }
    }

    /**
     * Reads the vault's list of files.
     *
     * The domain reports every way this can fail as its own state, so nothing here has to interpret
     * a failure: a list that cannot be read stays a list that cannot be read. An implementation that
     * throws instead is reported as unreachable rather than as an empty vault.
     */
    private suspend fun readIndex(): VaultIndexUiState = try {
        domainIndex = indexRepository.read()
        drawnIndex()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        domainIndex = VaultIndexState.Unavailable
        drawnIndex()
    }

    /**
     * The list as the screen draws it: the vault's items, filtered by the search box and ordered by
     * the chosen sort.
     *
     * Derived from the index that was already read rather than by reading again, so typing in the
     * search box and changing the sort cost nothing but this function. The counts that describe the
     * vault rather than the visible rows — missing content, unindexed and unfinished objects — are
     * taken from the index as a whole and are not affected by a query.
     */
    private fun drawnIndex(): VaultIndexUiState {
        val index = domainIndex ?: return VaultIndexUiState.Loading
        return index.toUiState(ordering = ordering, query = query)
    }

    /**
     * Reads the vault's albums.
     *
     * A record that cannot be read stays its own state: nothing here turns it into an empty album
     * list, because doing so would invite a person to start organising again over the top of albums
     * that are still on storage.
     */
    private suspend fun readOrganization() {
        domainOrganization = try {
            organizationRepository.read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            VaultOrganizationState.Unavailable
        }
    }

    /**
     * The albums as the screen draws them, each resolved against the index that was read.
     *
     * On the albums surface the search box filters them by title — album names are metadata Nivara
     * holds, and looking for one is the same kind of question as looking for a file. Membership is not
     * searched: an album is a list, not a property of the files in it.
     */
    private fun drawnOrganization(): VaultOrganizationUiState {
        val domain = domainOrganization ?: return VaultOrganizationUiState.Loading
        val index = domainIndex ?: VaultIndexState.Missing
        val visible = if (section == VaultSection.Albums) domain.matchingQuery() else domain
        return visible.toUiState { album -> album.resolveAgainst(index) }
    }

    /** The readable albums whose title matches the current query, or this state unchanged. */
    private fun VaultOrganizationState.matchingQuery(): VaultOrganizationState =
        if (query.isBlank) {
            this
        } else {
            when (this) {
                is VaultOrganizationState.Ready ->
                    VaultOrganizationState.Ready(albums = VaultSearch.filter(albums, query))

                else -> this
            }
        }

    /**
     * The open album, when one is open and still exists in the readable record.
     *
     * The items are the album's own, filtered by the same search box and ordered by the same sort
     * control as the vault's list — one collection of metadata, derived twice, rather than a second
     * index built for albums.
     */
    private fun drawnOpenAlbum(): VaultAlbumDetailUi? {
        val albumId = openAlbumId ?: return null
        val readable = domainOrganization as? VaultOrganizationState.Ready ?: return null
        val album = readable.album(albumId) ?: return null
        val contents = album.resolveAgainst(domainIndex ?: VaultIndexState.Missing)
        val drawn = when (contents) {
            is VaultAlbumContents.Resolved -> VaultAlbumContentsUi.Resolved(
                items = VaultSearch.filter(contents.items, query)
                    .inOrder(ordering)
                    .map { item -> item.toItemUi() },
                staleItemIds = contents.staleItemIds,
            )

            is VaultAlbumContents.Unresolved ->
                VaultAlbumContentsUi.Unresolved(index = contents.index.toUiState())
        }
        return VaultAlbumDetailUi(
            album = album.toAlbumUi(contents),
            contents = drawn,
            memberItemIds = album.itemIds.toSet(),
        )
    }

    /**
     * Rebuilds the drawn state from the last read, without touching the repository.
     *
     * Everything derived — the filtered and sorted list, the albums, the open album — is computed here
     * from the state that was already read, so a keystroke, a sort tap or an opened album is a pure
     * function of what the vault already said.
     */
    private fun publish() {
        val state = vaultState ?: return
        indexState = drawnIndex()
        val organization = drawnOrganization()
        val openAlbum = drawnOpenAlbum()
        // An album that is no longer in the record is not left open: the screen would otherwise draw a
        // detail card for something that was deleted.
        if (openAlbum == null) {
            openAlbumId = null
            editingAlbumItems = false
        }
        mutableUiState.value = VaultUiState.Ready(
            vault = state,
            index = indexState,
            organization = organization,
            section = section,
            searchQuery = queryText,
            search = searchState(indexState, organization),
            searchSummary = searchSummary(indexState, organization),
            ordering = ordering,
            openAlbum = openAlbum,
            renamingAlbumId = renamingAlbumId,
            confirmingAlbumDeleteId = confirmingDeleteId,
            editingAlbumItems = editingAlbumItems && openAlbum != null,
            sessionAuthenticated = sessionManager.currentState().isAuthenticated,
            busy = busy,
            importing = importing,
            progress = progress,
            unlockRequired = unlockRequired,
            failure = failure,
            noticeRes = noticeRes,
        )
    }

    /**
     * Whether the query found anything, could not be asked, or was not asked.
     *
     * The states are kept apart on purpose: "no file matches" is an answer about the vault, and "the
     * list cannot be read" is not — showing the second as the first would tell a person something
     * untrue about their own files.
     */
    private fun searchState(
        index: VaultIndexUiState,
        organization: VaultOrganizationUiState,
    ): VaultSearchUiState = when {
        query.isBlank -> VaultSearchUiState.NotAsked

        // On the albums surface the question is about albums, and the answer is about albums: an
        // unreadable record means the question could not be asked, which is not the same as "none".
        section == VaultSection.Albums -> when (organization) {
            is VaultOrganizationUiState.Albums ->
                if (organization.albums.isEmpty()) VaultSearchUiState.NoMatches else VaultSearchUiState.Matches

            VaultOrganizationUiState.Empty -> VaultSearchUiState.NoMatches
            else -> VaultSearchUiState.CannotSearch
        }

        // The domain answers this one, so the screen cannot invent a different set of rules: a vault
        // with no list record answers "nothing matches", and a list that cannot be read refuses to be
        // asked at all.
        else -> when (val asked = (domainIndex ?: VaultIndexState.Missing).search(query)) {
            VaultSearchResult.NotAsked -> VaultSearchUiState.NotAsked
            is VaultSearchResult.Found ->
                if (asked.items.isEmpty()) VaultSearchUiState.NoMatches else VaultSearchUiState.Matches

            is VaultSearchResult.CannotSearch -> VaultSearchUiState.CannotSearch
        }
    }

    /**
     * How many files answered, out of how many the vault holds.
     *
     * `null` whenever the query was not asked or the list could not be read, because a count drawn for
     * a list that was never read is a number invented for the occasion.
     */
    private fun searchSummary(
        index: VaultIndexUiState,
        organization: VaultOrganizationUiState,
    ): VaultSearchSummary? {
        if (query.isBlank) return null
        if (section == VaultSection.Albums) {
            val drawn = organization as? VaultOrganizationUiState.Albums ?: return null
            val total = (domainOrganization as? VaultOrganizationState.Ready)?.albums?.size ?: return null
            return VaultSearchSummary(matches = drawn.albums.size, total = total)
        }
        val domain = domainIndex ?: return null
        val total = when (domain) {
            is VaultIndexState.Ready -> domain.items.size
            VaultIndexState.Missing -> 0
            else -> return null
        }
        val matches = (index as? VaultIndexUiState.Indexed)?.items?.size ?: return null
        return VaultSearchSummary(matches = matches, total = total)
    }

    companion object {

        /**
         * How far the reported position must move before the screen is redrawn.
         *
         * A quarter of a megabyte is far below what a person can see and far above a single chunk, so
         * a large file redraws a handful of times rather than tens of thousands.
         */
        private const val PROGRESS_PUBLISH_BYTES = 256L * 1024

        /**
         * Factory that supplies the dependencies of [VaultViewModel] from the application container.
         *
         * Four dependencies, and no cryptographic one among them: the view model can show the vault's
         * state, ask for one to be created and ask for a file to be imported, and it has no way to
         * reach a key, a cipher or a credential. Importing is a request to the vault's own repository;
         * the key never leaves the data layer.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                VaultViewModel(
                    vaultRepository = container.vaultRepository,
                    indexRepository = container.vaultIndexRepository,
                    organizationRepository = container.vaultOrganizationRepository,
                    locationStore = container.vaultLocationStore,
                    sessionManager = container.sessionManager,
                )
            }
        }
    }
}

/** Turns a failure into the message the screen shows, defaulting when it is not a vault failure. */
private fun Throwable?.asMessage(): NivaraMessage =
    (this as? VaultFailure)?.asMessage() ?: vaultSelectionFailedMessage()
