package com.nivara.app.ui.home

import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.BiometricStatus

/**
 * State of the home screen.
 *
 * The three cases are the shape every Nivara screen will follow: an explicit loading state, a
 * ready state that carries data, and an error state that can be recovered from.
 */
sealed interface HomeUiState {

    /** The first result has not arrived yet. */
    data object Loading : HomeUiState

    /**
     * The screen has data to show.
     *
     * @param deviceLockConfigured whether the device currently has a screen lock set.
     * @param credentialType the configured primary credential, or `null` when none is set up.
     * @param biometricStatus whether the secondary biometric path is usable, set up or broken.
     */
    data class Ready(
        val deviceLockConfigured: Boolean,
        val credentialType: PrimaryCredentialType?,
        val biometricStatus: BiometricStatus,
    ) : HomeUiState

    /** The screen could not load. The UI offers a retry. */
    data object Error : HomeUiState
}
