package com.nivara.app.ui.camouflage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.isSuccess
import com.nivara.app.domain.camouflage.CamouflageProfile
import com.nivara.app.domain.camouflage.CamouflageRepository
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.ui.components.NivaraMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The application-identity screen's state machine.
 *
 * ### What it is responsible for
 *
 * Showing which identity the device is presenting, and carrying the user's choice to the repository
 * that owns that presentation — the same repository every other reader asks, so a screen and the
 * launcher can never disagree about the name on the icon.
 *
 * ### What it deliberately does not do
 *
 * It does not authenticate anybody. It asks the existing [SessionManager] whether the gate is open
 * and refuses a change when it is not; the credential and biometric paths that open the gate belong
 * to the screens that already implement them. It does not keep a selection of its own: the identity
 * it draws is the one the repository reported, read again after every change and on every resume,
 * so a change made anywhere else is shown rather than contradicted. And it knows nothing about
 * App Lock or hidden applications: identity, protection and hiding are three separate dimensions,
 * and nothing here reads, writes or depends on the other two.
 *
 * ### What an identity change requires, and why
 *
 * A change requires a valid session, like every other configuration change in Nivara. The reason is
 * not that camouflage protects anything — it does not, and the screen says so — but that renaming
 * the application and re-iconing its launcher entry is a change to how Nivara presents itself, and
 * anyone who can make it can also present the application as something the owner did not choose.
 * The rule is the same one the hidden-application screen follows, and it is the same gate.
 *
 * ### What survives the process
 *
 * The identity does: it is a platform component state, not a session. Whether the *user* is
 * authorised to change it does not: a new process has no session, so a change after a restart asks
 * for authentication again, and nothing about the previous session is reconstructed.
 */
class CamouflageViewModel(
    private val camouflageRepository: CamouflageRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<CamouflageUiState>(CamouflageUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<CamouflageUiState> = mutableUiState.asStateFlow()

    private var loaded: CamouflageProfile? = null
    private var busy: Boolean = false
    private var unlockRequired: Boolean = false
    private var failure: NivaraMessage? = null
    private var noticeRes: Int? = null

    init {
        read(showLoading = true)
        // The gate closes on its own — a timeout, or Quick Lock from anywhere in the application —
        // and it is the authority on whether a change may be made. Watching it keeps the controls
        // honest without the screen deciding for itself.
        viewModelScope.launch {
            sessionManager.state.collect { publish() }
        }
    }

    /** Re-reads the identity from the device, showing the loading state. */
    fun refresh() {
        read(showLoading = true)
    }

    /**
     * Re-reads the identity when the screen comes back to the foreground.
     *
     * This is how a change made outside Nivara — by Android, by a restore, by an administrator
     * resetting the application's components — is noticed, and it is why the screen never caches a
     * selection of its own.
     */
    fun onResumed() {
        read(showLoading = false)
    }

    /**
     * Presents [profile].
     *
     * With a valid session this asks the repository to change the identity and then re-reads it, so
     * what is drawn afterwards is what the device reports rather than what the tap asked for.
     * Without one it sets [CamouflageUiState.Ready.unlockRequired] and the screen sends the user to
     * the existing credential screen; nothing is changed either way. Selecting the identity that is
     * already presented does nothing at all — there is no change to make, and no notice to give.
     */
    fun select(profile: CamouflageProfile) {
        val current = mutableUiState.value
        if (current !is CamouflageUiState.Ready || current.busy) return
        if (profile == current.selected) return

        if (!sessionManager.currentState().isAuthenticated) {
            unlockRequired = true
            failure = camouflageLockedMessage()
            noticeRes = null
            publish()
            return
        }

        busy = true
        failure = null
        noticeRes = null
        publish()

        viewModelScope.launch {
            val result = try {
                camouflageRepository.selectProfile(profile)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The repository reports failures instead of throwing; a contract violation must
                // still not take the screen down, and must not be reported as a change.
                null
            }

            busy = false
            val changed = result != null && result.isSuccess
            failure = if (changed) null else camouflageChangeRefusedMessage()
            noticeRes = if (changed) R.string.camouflage_notice_changed else null
            // The outcome of a change is never assumed: the identity is read back from the device,
            // so the screen shows what is presented rather than what was requested.
            read(showLoading = false)
        }
    }

    /** Called once the screen has sent the user to the credential screen. */
    fun onUnlockHandled() {
        if (!unlockRequired) return
        unlockRequired = false
        publish()
    }

    /** Clears the last failure or confirmation, so a message does not outlive the moment. */
    fun onMessageShown() {
        if (failure == null && noticeRes == null) return
        failure = null
        noticeRes = null
        publish()
    }

    private fun read(showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) {
                mutableUiState.value = CamouflageUiState.Loading
            }
            val profile = try {
                camouflageRepository.currentProfile()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The contract says this cannot fail. If an implementation violates it anyway, the
                // default identity is drawn rather than no screen at all — and it is drawn as the
                // default, not as a guess at what the device might be showing.
                CamouflageProfile.Default
            }
            loaded = profile
            publish()
        }
    }

    /** Rebuilds the drawn state from the last read, without touching the repository. */
    private fun publish() {
        val selected = loaded ?: return
        mutableUiState.value = CamouflageUiState.Ready(
            selected = selected,
            sessionAuthenticated = sessionManager.currentState().isAuthenticated,
            busy = busy,
            unlockRequired = unlockRequired,
            failure = failure,
            noticeRes = noticeRes,
        )
    }

    companion object {

        /**
         * Factory that supplies the dependencies of [CamouflageViewModel] from the application
         * container.
         *
         * Two dependencies: the identity, and the session gate. No credential store, no hidden set,
         * no protected set and no monitor — this screen cannot reach any of them even by accident.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                CamouflageViewModel(
                    camouflageRepository = container.camouflageRepository,
                    sessionManager = container.sessionManager,
                )
            }
        }
    }
}
