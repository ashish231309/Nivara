package com.nivara.app.ui.applock

import androidx.annotation.StringRes
import com.nivara.app.R
import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.ui.components.NivaraMessage

/**
 * Copy for the App Lock preparation screen.
 *
 * Wording lives here rather than in the state machine, so the view model deals in states and the
 * screen deals in text, and no string resource identifier ends up in the domain layer.
 */

/** Short status word for the current Usage Access state. */
@StringRes
internal fun usageAccessStatusRes(status: UsageAccessStatus): Int = when (status) {
    UsageAccessStatus.Granted -> R.string.applock_setup_usage_access_granted
    UsageAccessStatus.NotGranted -> R.string.applock_setup_usage_access_not_granted
    UsageAccessStatus.Unavailable -> R.string.applock_setup_usage_access_unavailable
}

/** Name of a missing precondition, for the readiness summary. */
@StringRes
internal fun prerequisiteNameRes(prerequisite: AppLockPrerequisite): Int = when (prerequisite) {
    AppLockPrerequisite.ApplicationDiscovery -> R.string.applock_setup_prerequisite_discovery
    AppLockPrerequisite.UsageAccess -> R.string.applock_setup_prerequisite_usage_access
}

/**
 * The generic failure shown when Android's settings screen could not be opened.
 *
 * Deliberately generic: the platform's own message would name an intent or a component, and none
 * of that belongs on the screen.
 */
internal fun usageAccessSettingsUnavailableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.applock_setup_settings_unavailable)
