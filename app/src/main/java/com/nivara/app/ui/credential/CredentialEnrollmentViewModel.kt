package com.nivara.app.ui.credential

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which task the enrollment screen is doing. */
enum class CredentialFlowMode {
    /** First credential. Refused by the credential layer when one already exists. */
    Setup,

    /** Replacement. The credential layer authenticates the current credential before writing. */
    Change,
}

/** The steps of the enrollment flow. */
sealed interface CredentialEnrollmentStep {

    /** Pick PIN, password or pattern. */
    data object ChooseType : CredentialEnrollmentStep

    /** Enter the new credential. */
    data object EnterCredential : CredentialEnrollmentStep

    /** Enter it a second time. */
    data object ConfirmCredential : CredentialEnrollmentStep

    /** Change only: authenticate with the credential that is being replaced. */
    data object VerifyCurrent : CredentialEnrollmentStep

    /** Deriving and writing. */
    data object Working : CredentialEnrollmentStep

    /** The record is written. */
    data object Saved : CredentialEnrollmentStep
}

/**
 * State of the enrollment screen.
 *
 * The credential itself is *not* part of this state: the view model holds it in a separate
 * buffer that never reaches the UI, so nothing a screen renders can contain it.
 */
data class CredentialEnrollmentUiState(
    val mode: CredentialFlowMode,
    val step: CredentialEnrollmentStep = CredentialEnrollmentStep.ChooseType,
    val selectedType: PrimaryCredentialType = PrimaryCredentialType.Pin,
    val configuredType: PrimaryCredentialType? = null,
    val failure: CredentialMessage? = null,
)

/**
 * Drives credential setup and credential change.
 *
 * The two flows share everything except their last step, because they are the same operation
 * with different preconditions: [CredentialFlowMode.Change] additionally authenticates the
 * credential being replaced, and the credential layer performs that check inside the same call
 * that writes the new record. No "verified" flag is held anywhere in between — a state that
 * means "this device is unlocked" is a session concept, and sessions belong to a later stage.
 *
 * A failed operation always returns the user to the entry step with both entries discarded. The
 * buffers handed to the layer are cleared by the layer itself, so a failure cannot leave a
 * half-typed credential alive in memory waiting to be resubmitted.
 */
class CredentialEnrollmentViewModel(
    private val credentialManager: CredentialManager,
    val mode: CredentialFlowMode,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CredentialEnrollmentUiState(mode = mode))

    /** Current state, collected by the screen. */
    val uiState: StateFlow<CredentialEnrollmentUiState> = _uiState.asStateFlow()

    /** The new credential and its confirmation, held only while the flow needs both. */
    private var newCredential: CredentialInput? = null
    private var newConfirmation: CredentialInput? = null

    init {
        if (mode == CredentialFlowMode.Change) {
            loadConfiguredType()
        }
    }

    /** Records the chosen method and moves on to entering it. */
    fun selectType(type: PrimaryCredentialType) {
        _uiState.value = _uiState.value.copy(
            selectedType = type,
            step = CredentialEnrollmentStep.EnterCredential,
            failure = null,
        )
    }

    /** Takes the first entry and asks for the confirmation. */
    fun submitEntry(input: CredentialInput) {
        newCredential?.clear()
        newCredential = input
        _uiState.value = _uiState.value.copy(
            step = CredentialEnrollmentStep.ConfirmCredential,
            failure = null,
        )
    }

    /** Takes the confirmation and either saves, or asks for the current credential first. */
    fun submitConfirmation(input: CredentialInput) {
        val credential = newCredential
        if (credential == null) {
            input.clear()
            restart()
            return
        }

        if (mode == CredentialFlowMode.Change) {
            if (_uiState.value.configuredType == null) {
                // The stored record could not be read, so there is nothing to authenticate
                // against and nothing may be replaced.
                input.clear()
                complete(NivaraResult.Failure(CredentialFailure.InvalidConfiguration))
                return
            }
            newConfirmation?.clear()
            newConfirmation = input
            _uiState.value = _uiState.value.copy(
                step = CredentialEnrollmentStep.VerifyCurrent,
                failure = null,
            )
            return
        }

        newCredential = null
        start { credentialManager.enroll(credential, input) }
    }

    /** Authenticates with the current credential and replaces it. */
    fun submitCurrentCredential(input: CredentialInput) {
        val credential = newCredential
        val confirmation = newConfirmation
        if (credential == null || confirmation == null) {
            input.clear()
            restart()
            return
        }

        newCredential = null
        newConfirmation = null
        start { credentialManager.change(input, credential, confirmation) }
    }

    /** Abandons the current entries and goes back to choosing a method. */
    fun restart() {
        newCredential?.clear()
        newConfirmation?.clear()
        newCredential = null
        newConfirmation = null
        _uiState.value = _uiState.value.copy(step = CredentialEnrollmentStep.ChooseType, failure = null)
    }

    override fun onCleared() {
        newCredential?.clear()
        newConfirmation?.clear()
        newCredential = null
        newConfirmation = null
    }

    /** Runs [operation] off the caller's thread and folds its result into the state. */
    private fun start(operation: suspend () -> NivaraResult<Unit>) {
        _uiState.value = _uiState.value.copy(step = CredentialEnrollmentStep.Working, failure = null)
        viewModelScope.launch { complete(operation()) }
    }

    private fun complete(result: NivaraResult<Unit>) {
        _uiState.value = if (result is NivaraResult.Failure) {
            _uiState.value.copy(
                step = CredentialEnrollmentStep.EnterCredential,
                failure = (result.error as? CredentialFailure)?.toMessage() ?: GENERIC_FAILURE,
            )
        } else {
            _uiState.value.copy(step = CredentialEnrollmentStep.Saved, failure = null)
        }
    }

    private fun loadConfiguredType() {
        viewModelScope.launch {
            val status = credentialManager.status().valueOrNull()
            val configured = (status as? CredentialStatus.Configured)?.type
            _uiState.value = _uiState.value.copy(
                configuredType = configured,
                selectedType = configured ?: _uiState.value.selectedType,
            )
        }
    }

    companion object {
        private val GENERIC_FAILURE = CredentialMessage(R.string.credential_error_generic)

        /** Supplies the view model from the application container. */
        fun factory(mode: CredentialFlowMode): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                CredentialEnrollmentViewModel(
                    credentialManager = application.container.credentialManager,
                    mode = mode,
                )
            }
        }
    }
}
