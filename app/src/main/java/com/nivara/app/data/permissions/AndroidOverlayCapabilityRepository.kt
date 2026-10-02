package com.nivara.app.data.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [OverlayCapabilityRepository] built on the platform's overlay setting.
 *
 * ### Reading the state
 *
 * `Settings.canDrawOverlays` is the platform's own answer to "may this application draw above other
 * applications?", and reading it needs no permission: the same call Android uses to decide whether
 * to honour an overlay window. The deeper application-operation check is not repeated here; the
 * overlay path is documented as this setting, and duplicating the check would add a second source of
 * truth that could disagree with it.
 *
 * A read that fails at all becomes [OverlayCapability.Unavailable]: a broken check must never read
 * as a refusal, and it must certainly never read as a grant.
 *
 * ### Opening the screen
 *
 * The overlay setting lives in Android's own screen. The intent carries Nivara's *own* package name
 * — never a protected application's — so the user lands on Nivara's row instead of a list of every
 * application on the device. That is the whole of the data in the intent: no extras, no credentials
 * and nothing about what is protected.
 *
 * If a device has no screen that accepts that intent, the plain overlay-settings list is tried, and
 * a device with neither reports a failure rather than pretending to have shown something. A
 * successful call still means only that a screen was opened: the grant is read again afterwards and
 * is never assumed.
 */
class AndroidOverlayCapabilityRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OverlayCapabilityRepository {

    override suspend fun status(): OverlayCapability = withContext(dispatcher) {
        nivaraRunCatching { Settings.canDrawOverlays(context) }.valueOrNull().toCapability()
    }

    override suspend fun openSettings(): NivaraResult<Unit> = nivaraRunCatching {
        val packageSpecific = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // The package-specific screen is the one that shows a single row the user can act on; the
        // list is the fallback for devices whose settings implementation does not handle it.
        try {
            context.startActivity(packageSpecific)
        } catch (missingScreen: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** `null` — the read failed — is an unknown capability, never a refusal. */
private fun Boolean?.toCapability(): OverlayCapability = when (this) {
    true -> OverlayCapability.Granted
    false -> OverlayCapability.NotGranted
    null -> OverlayCapability.Unavailable
}
