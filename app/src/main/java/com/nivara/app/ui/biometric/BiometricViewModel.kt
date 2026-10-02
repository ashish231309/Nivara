package com.nivara.app.ui.biometric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.components.secondsFromMillis
import com.nivara.app.ui.credential.toFailureMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The biometric screen's state machine.
 *
 * Three rules are enforced here rather than in the composables, because they are the rules that
 * matter:
 *
 * - **Nothing changes a security control without the primary credential.** Turning biometric
 *   unlock on or off sets a pending change, and that change is carried out only after
 *   [submitPrimary] has verified the credential. A rejected credential leaves everything exactly
 *   as it was.
 * - **Android's answers are reported as Android's answers.** A cancelled prompt, a rejected
 *   biometric, Nivara's own delay and the platform's lockout are four different states with four
 *   different messages, and none of them is treated as a credential failure.
 * - **Every failure still ends at the credential.** Whatever went wrong, the screen keeps the way
 *   in that always works.
 *
 * No matching happens here and nothing here is verified against a sensor: the prompt and the key
 * belong to a device. What the JVM suite covers is the state machine above.
 */
class BiometricViewModel(
    private val authenticator: BiometricAuthenticator,
    private val credentialManager: CredentialManager,
    private val sessionManager: SessionManager,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<BiometricUiState>(BiometricUiState.Loading)

    val uiState: StateFlow<BiometricUiState> = mutableUiState.asStateFlow()

    /** The one operation in flight, if any. A later one replaces it rather than racing it. */
    private var operation: Job? = null

    init {
        refresh()
    }

    /** Reads the current state from the authenticator and from the credential. */
    fun refresh() {
        val previous = mutableUiState.value as? BiometricUiState.Ready
        start {
            mutableUiState.value = readState().copy(
                failure = previous?.failure,
                noticeRes = previous?.noticeRes,
            )
        }
    }

    /**
     * Asks Android to authenticate with the stored key.
     *
     * The prompt appears only when there is something to authenticate: a path that is not
     * configured, or one Nivara is currently delaying, is answered from state, so the delay is
     * never spent on a prompt and the sensor is never reached behind it.
     */
    fun authenticate() {
        val ready = readyStateOrNull() ?: return
        if (ready.busy) return
        mutableUiState.value = ready.copy(busy = true, failure = null, noticeRes = null)

        start {
            val outcome = withContext(backgroundDispatcher) { authenticator.authenticate() }
            // A biometric success is a valid factor for the session, and the only thing that turns
            // it into one: the authenticator reports what happened, the gate decides what it opens.
            // Every other outcome leaves the session exactly as it was.
            sessionManager.establish(outcome)
            // The state read after the attempt is authoritative: it carries the failure count and
            // whatever delay Nivara now applies, which is not something the outcome has to restate.
            mutableUiState.value = readState().copy(
                busy = false,
                failure = outcome.toMessage(),
                noticeRes = if (outcome is BiometricAuthenticationOutcome.Succeeded) {
                    R.string.biometric_notice_authenticated
                } else {
                    null
                },
            )
        }
    }

    /** Asks for a change to be made, pending the primary credential. */
    fun requestChange(change: BiometricPendingChange) {
        val ready = readyStateOrNull() ?: return
        if (ready.busy) return
        mutableUiState.value = ready.copy(pending = change, failure = null, noticeRes = null)
    }

    /** Drops a pending change. Nothing was done, so nothing has to be undone. */
    fun cancelChange() {
        val ready = readyStateOrNull() ?: return
        mutableUiState.value = ready.copy(pending = null, failure = null)
    }

    /**
     * Verifies the primary credential and carries out the pending change if it is accepted.
     *
     * A rejected credential is reported and the pending change stays on screen, so the user can
     * try again or back out. The buffer belongs to the credential manager, which clears it.
     */
    fun submitPrimary(input: CredentialInput) {
        val ready = readyStateOrNull() ?: return
        val pending = ready.pending ?: return
        if (ready.busy) return
        mutableUiState.value = ready.copy(busy = true, failure = null, noticeRes = null)

        start {
            val outcome = withContext(backgroundDispatcher) { credentialManager.verify(input) }
            val rejection = outcome.toFailureMessage()
            if (rejection != null) {
                val current = readyStateOrNull() ?: return@start
                mutableUiState.value = current.copy(busy = false, failure = rejection)
                return@start
            }

            // The primary credential was verified here, so this is a primary authentication like
            // any other: it opens a session before the pending change is carried out.
            sessionManager.establish(outcome)

            val result = withContext(backgroundDispatcher) { apply(pending) }
            val failure = result.toMessage()
            mutableUiState.value = readState().copy(
                pending = null,
                busy = false,
                failure = failure,
                noticeRes = if (failure == null) noticeFor(pending) else null,
            )
        }
    }

    /** Whole seconds left of Nivara's own delay, or zero when there is none. */
    fun secondsUntilRetry(state: BiometricUiState.Ready): Long =
        secondsFromMillis((state.retryAtMillis ?: clockMillis()) - clockMillis())

    private suspend fun apply(change: BiometricPendingChange): NivaraResult<Unit> = when (change) {
        BiometricPendingChange.TurnOn -> authenticator.enable()
        BiometricPendingChange.TurnOff -> authenticator.disable()
        BiometricPendingChange.ClearDelay -> {
            authenticator.clearFailures()
            NivaraResult.Success(Unit)
        }
    }

    private suspend fun readState(): BiometricUiState.Ready {
        val state = withContext(backgroundDispatcher) { authenticator.state() }
        val credential = withContext(backgroundDispatcher) { credentialManager.status() }
        val configured = credential.valueOrNull() as? CredentialStatus.Configured
        return BiometricUiState.Ready(
            status = state.status,
            credentialType = configured?.type,
            retryAtMillis = if (state.retryAfterMillis > 0L) {
                clockMillis() + state.retryAfterMillis
            } else {
                null
            },
        )
    }

    private fun readyStateOrNull(): BiometricUiState.Ready? = uiState.value as? BiometricUiState.Ready

    private fun start(block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch { block() }
    }

    private fun noticeFor(change: BiometricPendingChange): Int = when (change) {
        BiometricPendingChange.TurnOn -> R.string.biometric_notice_enabled
        BiometricPendingChange.TurnOff -> R.string.biometric_notice_disabled
        BiometricPendingChange.ClearDelay -> R.string.biometric_notice_delay_cleared
    }

    /** The message for a failed change, or `null` when it went through. */
    private fun NivaraResult<Unit>.toMessage(): NivaraMessage? {
        val failure = this as? NivaraResult.Failure ?: return null
        // The authenticator reports why a change failed with its own typed failures; anything else
        // is still reported rather than swallowed.
        return (failure.error as? BiometricFailure)?.toMessage()
            ?: NivaraMessage(R.string.biometric_error_generic)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                BiometricViewModel(
                    authenticator = container.biometricAuthenticator,
                    credentialManager = container.credentialManager,
                    sessionManager = container.sessionManager,
                )
            }
        }
    }
}
