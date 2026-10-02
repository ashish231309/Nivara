package com.nivara.app.ui.vault.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultRecoveryFailure
import com.nivara.app.domain.vault.VaultRecoveryRepository
import com.nivara.app.domain.vault.VaultRecoverySurvey
import com.nivara.app.domain.vault.displayFingerprint
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.components.secondsFromMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the recovery screen: surveying a folder the user selects, taking the recovery code, and
 * reporting the reconnected vault.
 *
 * The view model holds no key material and no reference to the selected folder beyond the
 * domain's opaque [VaultLocation]: the survey, the unwrapping and the reconnection all happen in
 * the repository, and what arrives here is state to draw. Reaching the screen is ordinary
 * navigation under the app's existing session rules; reconnecting a vault is possession of the
 * vault's key, which is not the same thing as being authenticated to the app, and the screen
 * offers no session of its own.
 */
class VaultRecoveryViewModel(
    private val recoveryRepository: VaultRecoveryRepository,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(VaultRecoveryUiState())

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<VaultRecoveryUiState> = mutableUiState.asStateFlow()

    private var candidate: VaultLocation? = null

    /**
     * Takes the folder the user picked and surveys it.
     *
     * The survey reads only clear structure and adopts nothing: whatever it finds, the folder is
     * not remembered until a recovery attempt succeeds.
     */
    fun onLocationPicked(reference: String) {
        val current = mutableUiState.value
        if (current.busy) return

        val location = VaultLocation.create(reference)
        if (location == null) {
            publish(
                phase = VaultRecoveryPhase.SelectLocation,
                failure = NivaraMessage(textRes = R.string.vault_recovery_location_unavailable),
            )
            return
        }
        candidate = location

        publish(phase = current.phase, busy = true, failure = null)
        viewModelScope.launch {
            val result = try {
                recoveryRepository.surveyRecovery(location)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                NivaraResult.Failure(error)
            }
            val survey = when (result) {
                is NivaraResult.Success -> result.value
                is NivaraResult.Failure -> {
                    publish(
                        phase = VaultRecoveryPhase.LocationUnavailable,
                        failure = result.error.asRecoveryMessage(),
                    )
                    return@launch
                }
            }
            when (survey) {
                VaultRecoverySurvey.NotAVault -> publish(phase = VaultRecoveryPhase.NotAVault)
                VaultRecoverySurvey.RecoveryNotSetUp ->
                    publish(phase = VaultRecoveryPhase.RecoveryNotSetUp)
                VaultRecoverySurvey.VaultDamaged ->
                    publish(phase = VaultRecoveryPhase.VaultDamaged)
                VaultRecoverySurvey.VaultUnsupported ->
                    publish(phase = VaultRecoveryPhase.VaultUnsupported)
                VaultRecoverySurvey.Unavailable ->
                    publish(phase = VaultRecoveryPhase.LocationUnavailable)
                is VaultRecoverySurvey.RecoveryAvailable -> publish(
                    phase = VaultRecoveryPhase.RecoveryRequired(
                        identityFingerprint = survey.identityFingerprint,
                    ),
                )
            }
        }
    }

    /** Keeps the code field as the user types it; nothing else looks at it. */
    fun onCodeChanged(code: String) {
        val current = mutableUiState.value
        if (current.busy) return
        mutableUiState.value = current.copy(codeInput = code, failure = null)
    }

    /**
     * Attempts the reconnection with the entered code.
     *
     * Success lands on the reconnected phase with the vault's fingerprint; every failure lands on
     * a typed message and the attempt can be repeated from the same phase.
     */
    fun onRecoverRequested() {
        val location = candidate ?: return
        val current = mutableUiState.value
        if (current.busy) return
        val phase = current.phase
        if (phase !is VaultRecoveryPhase.RecoveryRequired) return

        publish(phase = phase, busy = true, failure = null)
        viewModelScope.launch {
            val result = try {
                recoveryRepository.recover(location = location, code = current.codeInput)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                NivaraResult.Failure(error)
            }
            when (result) {
                is NivaraResult.Success<VaultIdentity> -> publish(
                    phase = VaultRecoveryPhase.Reconnected(
                        identityFingerprint = result.value.displayFingerprint(),
                    ),
                    codeInput = "",
                )
                is NivaraResult.Failure -> publish(
                    phase = phase,
                    failure = result.error.asRecoveryMessage(),
                )
            }
        }
    }

    /** Starts over with a different folder; the previous attempt's state is dropped. */
    fun onRestartRequested() {
        val current = mutableUiState.value
        if (current.busy) return
        candidate = null
        publish(phase = VaultRecoveryPhase.SelectLocation, codeInput = "")
    }

    private fun publish(
        phase: VaultRecoveryPhase,
        busy: Boolean = false,
        failure: NivaraMessage? = null,
        codeInput: String = mutableUiState.value.codeInput,
    ) {
        mutableUiState.value = VaultRecoveryUiState(
            phase = phase,
            busy = busy,
            codeInput = codeInput,
            failure = failure,
        )
    }

    companion object {

        /**
         * Factory that supplies the dependencies of [VaultRecoveryViewModel] from the application
         * container.
         *
         * One dependency, and no cryptographic one among them: the view model can show recovery's
         * state and hand the repository a folder reference and a code, and it has no way to reach
         * a key, a cipher or a credential.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                VaultRecoveryViewModel(
                    recoveryRepository = application.container.vaultRecoveryRepository,
                )
            }
        }
    }
}

/** Turns a recovery failure into the message the screen shows, defaulting when it is not one. */
internal fun Throwable?.asRecoveryMessage(): NivaraMessage = when (this) {
    VaultRecoveryFailure.NotAVault ->
        NivaraMessage(textRes = R.string.vault_recovery_not_a_vault)
    VaultRecoveryFailure.RecoveryNotSetUp ->
        NivaraMessage(textRes = R.string.vault_recovery_not_set_up)
    VaultRecoveryFailure.VaultDamaged ->
        NivaraMessage(textRes = R.string.vault_recovery_damaged)
    VaultRecoveryFailure.VaultUnsupported ->
        NivaraMessage(textRes = R.string.vault_recovery_unsupported)
    VaultRecoveryFailure.LocationUnavailable ->
        NivaraMessage(textRes = R.string.vault_recovery_location_unavailable)
    VaultRecoveryFailure.CodeMalformed ->
        NivaraMessage(textRes = R.string.vault_recovery_code_malformed)
    VaultRecoveryFailure.CodeChecksumMismatch ->
        NivaraMessage(textRes = R.string.vault_recovery_code_checksum)
    VaultRecoveryFailure.WrongMaterial ->
        NivaraMessage(textRes = R.string.vault_recovery_wrong_material)
    VaultRecoveryFailure.KeyMismatch ->
        NivaraMessage(textRes = R.string.vault_recovery_key_mismatch)
    is VaultRecoveryFailure.Locked -> NivaraMessage(
        textRes = R.string.vault_recovery_locked,
        argument = secondsFromMillis(remainingMillis),
    )
    VaultRecoveryFailure.WriteFailed ->
        NivaraMessage(textRes = R.string.vault_recovery_write_failed)
    else -> NivaraMessage(textRes = R.string.vault_recovery_failed)
}
