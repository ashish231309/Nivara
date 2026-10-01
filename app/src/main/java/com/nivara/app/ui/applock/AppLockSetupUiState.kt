package com.nivara.app.ui.applock

import com.nivara.app.domain.permissions.AppLockSetupState
import com.nivara.app.ui.components.NivaraMessage

/**
 * State of the App Lock preparation screen.
 *
 * The same three-case shape as every other Nivara screen: an explicit loading state, a ready state
 * that carries the capability answers, and an error state that can be retried.
 */
sealed interface AppLockSetupUiState {

    /** The first result has not arrived yet. */
    data object Loading : AppLockSetupUiState

    /**
     * The screen has an answer, whether or not the prerequisites are satisfied.
     *
     * @param setup what App Lock has and what it is missing.
     * @param busy `true` while Android's settings screen is being opened.
     * @param failure why the last action failed, when it did. Generic copy only: no platform
     *   exception, package name or settings detail reaches the screen.
     * @param noticeRes a short confirmation, used when the user returns from Android's settings
     *   with Usage Access newly granted.
     */
    data class Ready(
        val setup: AppLockSetupState,
        val busy: Boolean = false,
        val failure: NivaraMessage? = null,
        val noticeRes: Int? = null,
    ) : AppLockSetupUiState

    /** The screen could not load. The UI offers a retry. */
    data object Error : AppLockSetupUiState
}
