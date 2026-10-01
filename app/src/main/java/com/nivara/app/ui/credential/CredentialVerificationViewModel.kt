package com.nivara.app.ui.credential

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.ui.components.NivaraMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The steps of the verification screen. */
sealed interface CredentialVerificationStep {

    /** Reading the stored configuration. */
    data object Loading : CredentialVerificationStep

    /** Waiting for the credential. */
    data object Enter : CredentialVerificationStep

    /** Deriving and comparing. */
    data object Verifying : CredentialVerificationStep

    /** The credential was accepted. */
    data object Succeeded : CredentialVerificationStep

    /** Nothing is configured yet. */
    data object NotConfigured : CredentialVerificationStep

    /** The stored record cannot be used. */
    data object InvalidConfiguration : CredentialVerificationStep
}

/**
 * State of the verification screen.
 *
 * [failure] is a message rather than the underlying failure, so no detail about the stored data
 * or the derivation reaches the UI layer. [type] drives which entry widget is shown; it is the
 * *configured* method, so a user whose credential is a pattern never gets a password field.
 *
 * [session] is what the session gate decided, and it is what makes the success step honest: the
 * screen is showing that something is open only while a session really is. When the session ends —
 * a timeout, or a lock from anywhere in the application — [notice] says so and the screen returns
 * to asking for the credential.
 */
data class CredentialVerificationUiState(
    val step: CredentialVerificationStep = CredentialVerificationStep.Loading,
    val type: PrimaryCredentialType? = null,
    val failure: NivaraMessage? = null,
    val session: SessionState = SessionState.Unauthenticated,
    val notice: NivaraMessage? = null,
)

/**
 * Drives credential verification.
 *
 * The screen never sees the credential, the derived key or the stored verifier — it hands over
 * an entry buffer and receives one of the outcomes the credential layer defines.
 *
 * The view model does not decide *whether* the user is authenticated: it hands the finished
 * outcome to the [SessionManager] and renders what comes back. That is the whole integration — an
 * accepted credential and an accepted biometric both arrive at the same gate, and neither the
 * screen nor the credential layer keeps an unlock flag of its own.
 */
class CredentialVerificationViewModel(
    private val credentialManager: CredentialManager,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CredentialVerificationUiState())

    /** Current state, collected by the screen. */
    val uiState: StateFlow<CredentialVerificationUiState> = _uiState.asStateFlow()

    init {
        refresh()
        // The gate closes on its own when the timeout passes, and immediately on Quick Lock.
        // Watching it here is what stops an already-expired session from still being on screen.
        viewModelScope.launch {
            sessionManager.state.collect { session -> applySession(session) }
        }
    }

    /** Ends the session now, from this screen. */
    fun lockNow() {
        sessionManager.lockNow()
    }

    private fun applySession(session: SessionState) {
        val current = _uiState.value
        _uiState.value = if (!session.isAuthenticated && current.step == CredentialVerificationStep.Succeeded) {
            current.copy(
                step = CredentialVerificationStep.Enter,
                session = session,
                notice = NivaraMessage(R.string.session_notice_ended),
            )
        } else {
            current.copy(session = session)
        }
    }

    /** Loads which method is configured. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = CredentialVerificationUiState()

            // `null` means the status could not be read, which is an unusable configuration
            // rather than a missing credential.
            val session = sessionManager.currentState()
            _uiState.value = when (val status = credentialManager.status().valueOrNull()) {
                null -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.InvalidConfiguration,
                    session = session,
                )
                is CredentialStatus.Configured -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Enter,
                    type = status.type,
                    session = session,
                )
                is CredentialStatus.NotConfigured -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.NotConfigured,
                    session = session,
                )
            }
        }
    }

    /** Verifies one attempt. */
    fun submit(input: CredentialInput) {
        val type = _uiState.value.type
        if (type == null) {
            input.clear()
            refresh()
            return
        }

        _uiState.value = _uiState.value.copy(
            step = CredentialVerificationStep.Verifying,
            failure = null,
            notice = null,
        )
        viewModelScope.launch {
            _uiState.value = when (val outcome = credentialManager.verify(input)) {
                // The credential layer has authenticated; the gate decides what that opens. The
                // outcome is passed on unchanged, so the session can never be opened by anything
                // other than a real success.
                is AuthenticationOutcome.Succeeded -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Succeeded,
                    // The configured method is kept: it is not a secret, it is already on screen,
                    // and the screen needs it to offer the entry field again when the session ends.
                    type = type,
                    session = sessionManager.establish(outcome),
                )
                is AuthenticationOutcome.NotConfigured -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.NotConfigured,
                    session = sessionManager.currentState(),
                )
                is AuthenticationOutcome.InvalidConfiguration -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.InvalidConfiguration,
                    session = sessionManager.currentState(),
                )
                // A rejection never opens anything, and never closes anything either: a failed
                // attempt is not a reason to drop a session that is already open.
                is AuthenticationOutcome.Failed -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Enter,
                    type = type,
                    failure = outcome.toFailureMessage(),
                    session = sessionManager.currentState(),
                )
                is AuthenticationOutcome.TemporarilyBlocked -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Enter,
                    type = type,
                    failure = outcome.toFailureMessage(),
                    session = sessionManager.currentState(),
                )
            }
        }
    }

    companion object {
        /** Supplies the view model from the application container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                CredentialVerificationViewModel(
                    credentialManager = application.container.credentialManager,
                    sessionManager = application.container.sessionManager,
                )
            }
        }
    }
}
