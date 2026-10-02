package com.nivara.app.domain.app

import com.nivara.app.core.common.NivaraResult

/**
 * Opens Android's Home-application settings, where the user chooses the device's default Home
 * application.
 *
 * Nivara never makes itself the default; the choice is Android's own screen and the user's own
 * act. This contract only opens that screen, and reports a failure when the device has none,
 * rather than pretending to have shown something.
 */
interface HomeSettingsOpener {

    /** Opens the platform's Home settings. A success only means the screen was opened. */
    fun openHomeSettings(): NivaraResult<Unit>
}
