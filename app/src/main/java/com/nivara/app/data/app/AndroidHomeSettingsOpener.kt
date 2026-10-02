package com.nivara.app.data.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.app.HomeSettingsOpener

/**
 * [HomeSettingsOpener] backed by the platform's Home settings screen.
 *
 * The package-specific default-home screen where some devices land is not part of the public
 * settings contract, so the plain Home-settings action is used as-is: it always shows the user
 * the choice of Home application, and that is the whole of what Nivara may offer.
 */
class AndroidHomeSettingsOpener(
    private val context: Context,
) : HomeSettingsOpener {

    override fun openHomeSettings(): NivaraResult<Unit> = nivaraRunCatching {
        try {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (missingScreen: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
