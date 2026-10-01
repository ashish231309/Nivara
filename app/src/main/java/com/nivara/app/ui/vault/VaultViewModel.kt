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
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationStore
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultState
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
    private val locationStore: VaultLocationStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<VaultUiState>(VaultUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<VaultUiState> = mutableUiState.asStateFlow()

    private var vaultState: VaultState? = null
    private var indexState: VaultIndexUiState = VaultIndexUiState.Loading
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
            indexState = if (state is VaultState.Ready) readIndex() else VaultIndexUiState.VaultNotReady
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
        indexRepository.read().toUiState()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        VaultIndexUiState.Unavailable
    }

    /** Rebuilds the drawn state from the last read, without touching the repository. */
    private fun publish() {
        val state = vaultState ?: return
        mutableUiState.value = VaultUiState.Ready(
            vault = state,
            index = indexState,
            sessionAuthenticated = sessionManager.currentState().isAuthenticated,
            busy = busy,
            importing = importing,
            progress = progress,
            unlockRequired = unlockRequired,
            failure = failure,
            noticeRes = noticeRes,
        )
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
