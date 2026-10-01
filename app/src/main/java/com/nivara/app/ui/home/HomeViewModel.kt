package com.nivara.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.DeviceSecurityProvider
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presents the home screen.
 *
 * The view model depends on domain contracts — [DeviceSecurityProvider], [CredentialManager],
 * [BiometricAuthenticator] and [SessionManager] — rather than on Android or data-layer classes,
 * which keeps it testable on the JVM and independent of how the platform reports device state,
 * stores the credential, performs a biometric match or holds the session.
 *
 * The session is observed rather than polled: when the manager closes it — because the timeout
 * passed, or because the user locked Nivara — the card changes on its own, and the screen never
 * works out for itself whether a session is still good.
 */
class HomeViewModel(
    private val deviceSecurityProvider: DeviceSecurityProvider,
    private val credentialManager: CredentialManager,
    private val biometricAuthenticator: BiometricAuthenticator,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
        // The authoritative session transitions (timeout, Quick Lock, a new authentication) are
        // pushed by the manager; this mirrors them into the screen's state.
        viewModelScope.launch {
            sessionManager.state.collect { session -> applySession(session) }
        }
    }

    /** Locks Nivara immediately. The session is gone before this returns. */
    fun lockNow() {
        sessionManager.lockNow()
        applySession(sessionManager.currentState(), noticeRes = R.string.session_notice_locked)
    }

    private fun applySession(session: SessionState, noticeRes: Int? = null) {
        val current = _uiState.value
        if (current is HomeUiState.Ready) {
            _uiState.value = current.copy(
                session = session,
                sessionNoticeRes = noticeRes ?: current.sessionNoticeRes,
            )
        }
    }

    /** Loads the device security status and the credential status, showing the loading state. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            _uiState.value = load()
        }
    }

    private suspend fun load(): HomeUiState {
        val deviceLockConfigured = deviceSecurityProvider.isDeviceLockConfigured().valueOrNull()
            ?: return HomeUiState.Error
        val credentialStatus = credentialManager.status().valueOrNull()
            ?: return HomeUiState.Error
        val biometricStatus = biometricAuthenticator.state().status
        // Read through the gate, so a session that ran out while this screen was away is already
        // closed by the time the card is drawn.
        val session = sessionManager.currentState()

        val credentialType = when (credentialStatus) {
            is CredentialStatus.Configured -> credentialStatus.type
            is CredentialStatus.NotConfigured -> null
        }

        return HomeUiState.Ready(
            deviceLockConfigured = deviceLockConfigured,
            credentialType = credentialType,
            biometricStatus = biometricStatus,
            session = session,
        )
    }

    companion object {
        /**
         * Factory that supplies the dependencies of [HomeViewModel] from the application
         * container. Used by `viewModel(factory = …)` at the call site.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                HomeViewModel(
                    deviceSecurityProvider = application.container.deviceSecurityProvider,
                    credentialManager = application.container.credentialManager,
                    biometricAuthenticator = application.container.biometricAuthenticator,
                    sessionManager = application.container.sessionManager,
                )
            }
        }
    }
}
