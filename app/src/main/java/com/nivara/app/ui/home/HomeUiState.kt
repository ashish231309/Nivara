package com.nivara.app.ui.home

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
     */
    data class Ready(val deviceLockConfigured: Boolean) : HomeUiState

    /** The screen could not load. The UI offers a retry. */
    data object Error : HomeUiState
}
