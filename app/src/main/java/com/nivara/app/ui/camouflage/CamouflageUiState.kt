package com.nivara.app.ui.camouflage

import com.nivara.app.domain.camouflage.CamouflageProfile
import com.nivara.app.ui.components.NivaraMessage

/**
 * State of the application-identity screen.
 *
 * There is no error state, and that is a statement about the contract rather than an omission:
 * [com.nivara.app.domain.camouflage.CamouflageRepository.currentProfile] cannot fail — a device
 * whose components do not describe an identity is repaired to the default one and reported as such
 * — so the screen always has an identity to draw. What can fail is a *change*, and a failure there
 * is reported as a message on the ready state rather than by replacing the screen, because the
 * identity the user is looking at is still the truth about the device.
 *
 * Nothing here is persisted by Nivara and nothing here survives the process as Nivara's own state:
 * the selection lives in the platform's component states, and a new process reads it back. The
 * session does not survive, so a change after a restart asks for authentication again.
 */
sealed interface CamouflageUiState {

    /** The identity has not been read yet. */
    data object Loading : CamouflageUiState

    /** The identity the device is presenting, and what the user may do about it. */
    data class Ready(
        /** The identity the device is presenting now. */
        val selected: CamouflageProfile,

        /** Whether Nivara currently has a valid session. A change is refused while this is `false`. */
        val sessionAuthenticated: Boolean,

        /** `true` while a change is being applied. Every control is disabled meanwhile. */
        val busy: Boolean = false,

        /**
         * `true` when the user asked for a change and has no valid session.
         *
         * The screen answers this by sending the user to the existing credential screen; it never
         * authenticates, and it never changes anything by itself.
         */
        val unlockRequired: Boolean = false,

        /** Why the last change failed, when it did. Generic copy only. */
        val failure: NivaraMessage? = null,

        /** A short confirmation for the last change, when it succeeded. */
        val noticeRes: Int? = null,
    ) : CamouflageUiState {

        /**
         * Whether a change may be attempted at all: the gate must be open and no change may be in
         * flight. There is no third condition — the identity is always readable — and no second
         * authentication to satisfy.
         */
        val canChange: Boolean get() = sessionAuthenticated && !busy
    }
}
