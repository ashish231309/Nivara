package com.nivara.app.ui.credential

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
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
 */
data class CredentialVerificationUiState(
    val step: CredentialVerificationStep = CredentialVerificationStep.Loading,
    val type: PrimaryCredentialType? = null,
    val failure: CredentialMessage? = null,
)

/**
 * Drives credential verification.
 *
 * The screen never sees the credential, the derived key or the stored verifier — it hands over
 * an entry buffer and receives one of the outcomes the credential layer defines.
 */
class CredentialVerificationViewModel(
    private val credentialManager: CredentialManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CredentialVerificationUiState())

    /** Current state, collected by the screen. */
    val uiState: StateFlow<CredentialVerificationUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Loads which method is configured. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = CredentialVerificationUiState()

            // `null` means the status could not be read, which is an unusable configuration
            // rather than a missing credential.
            _uiState.value = when (val status = credentialManager.status().valueOrNull()) {
                null -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.InvalidConfiguration,
                )
                is CredentialStatus.Configured -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Enter,
                    type = status.type,
                )
                is CredentialStatus.NotConfigured -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.NotConfigured,
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

        _uiState.value = _uiState.value.copy(step = CredentialVerificationStep.Verifying, failure = null)
        viewModelScope.launch {
            _uiState.value = when (val outcome = credentialManager.verify(input)) {
                is AuthenticationOutcome.Succeeded -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Succeeded,
                )
                is AuthenticationOutcome.NotConfigured -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.NotConfigured,
                )
                is AuthenticationOutcome.InvalidConfiguration -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.InvalidConfiguration,
                )
                is AuthenticationOutcome.Failed -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Enter,
                    type = type,
                    failure = outcome.toFailureMessage(),
                )
                is AuthenticationOutcome.TemporarilyBlocked -> CredentialVerificationUiState(
                    step = CredentialVerificationStep.Enter,
                    type = type,
                    failure = outcome.toFailureMessage(),
                )
            }
        }
    }

    companion object {
        /** Supplies the view model from the application container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                CredentialVerificationViewModel(credentialManager = application.container.credentialManager)
            }
        }
    }
}
