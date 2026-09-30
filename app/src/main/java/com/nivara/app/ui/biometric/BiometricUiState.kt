package com.nivara.app.ui.biometric

import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.ui.components.NivaraMessage

/** What the biometric screen is showing. */
sealed interface BiometricUiState {

    /** Nothing has been read yet. */
    data object Loading : BiometricUiState

    /**
     * The screen has something to say.
     *
     * @param status what Nivara's biometric configuration is, given the platform's answer about the
     *   key and the hardware.
     * @param credentialType the configured primary credential, or `null` when there is none. With
     *   no credential, biometric unlock cannot be turned on at all.
     * @param pending the change waiting for the primary credential, if the user asked for one. A
     *   change is only ever carried out after the credential has been verified.
     * @param busy an operation is in flight — including a prompt that is on screen.
     * @param failure why the last operation did not finish, in plain terms.
     * @param noticeRes a short confirmation of the last successful operation.
     * @param retryAtMillis when Nivara's own delay ends, or `null` when there is none. Android's
     *   lockout has no deadline here because Nivara cannot know it.
     */
    data class Ready(
        val status: BiometricStatus,
        val credentialType: PrimaryCredentialType?,
        val pending: BiometricPendingChange? = null,
        val busy: Boolean = false,
        val failure: NivaraMessage? = null,
        val noticeRes: Int? = null,
        val retryAtMillis: Long? = null,
    ) : BiometricUiState
}

/**
 * A change the user asked for, held until the primary credential has confirmed it.
 *
 * Every one of these alters a security control or clears a delay, so none of them happens on the
 * strength of a button press alone.
 */
sealed interface BiometricPendingChange {

    /** Create the key and the record, after Android has authenticated the user once. */
    data object TurnOn : BiometricPendingChange

    /** Remove the record and the key. */
    data object TurnOff : BiometricPendingChange

    /** Forget Nivara's own failure count. */
    data object ClearDelay : BiometricPendingChange
}
