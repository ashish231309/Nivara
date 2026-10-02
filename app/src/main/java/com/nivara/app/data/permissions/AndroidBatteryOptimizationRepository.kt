package com.nivara.app.data.permissions

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.permissions.BatteryOptimizationRepository
import com.nivara.app.domain.permissions.BatteryOptimizationStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [BatteryOptimizationRepository] built on the platform's power manager and battery settings.
 *
 * ### Reading the state
 *
 * `PowerManager.isIgnoringBatteryOptimizations` for Nivara's own package is the platform's own
 * answer, and reading it needs no permission. A missing service or a failed check becomes
 * [BatteryOptimizationStatus.Unavailable] rather than an exception or a refusal.
 *
 * ### Asking for the exemption
 *
 * Android offers a system dialog for exactly this — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * with Nivara's own package — so the onboarding screen can present one tap that ends in a
 * platform confirmation rather than a settings hunt. Devices without that dialog fall back to
 * the battery-optimization settings list, and a device with neither reports a failure rather
 * than pretending to have shown something. A successful call still means only that a surface
 * was shown: the exemption is read again afterwards and is never assumed.
 */
class AndroidBatteryOptimizationRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BatteryOptimizationRepository {

    override suspend fun status(): BatteryOptimizationStatus = withContext(dispatcher) {
        nivaraRunCatching { readStatus() }.valueOrNull() ?: BatteryOptimizationStatus.Unavailable
    }

    @SuppressLint("BatteryLife")
    override suspend fun requestExemption(): NivaraResult<Unit> = nivaraRunCatching {
        val packageUri = Uri.fromParts("package", context.packageName, null)

        // The system confirmation is the shortest honest path to the exemption.
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return@nivaraRunCatching
        } catch (missingDialog: ActivityNotFoundException) {
            // Fall through to the settings screens.
        }

        // The package-specific battery screen, then the plain list, then give up honestly.
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (missingScreen: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun readStatus(): BatteryOptimizationStatus {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return BatteryOptimizationStatus.Unavailable
        return if (power.isIgnoringBatteryOptimizations(context.packageName)) {
            BatteryOptimizationStatus.Granted
        } else {
            BatteryOptimizationStatus.NotGranted
        }
    }
}
