package com.nivara.app.data.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [UsageAccessRepository] built on the platform's application-operation state and settings screen.
 *
 * Two things are deliberately absent.
 *
 * - **No request.** Usage Access has no runtime dialog. `requestPermissions` is never involved, and
 *   nothing in this class can grant the capability — [openSettings] only hands the user to
 *   Android's own screen, and the state is read again when they come back.
 * - **No usage data.** Nothing here reads statistics. The presence of the grant is a capability
 *   check; the stage that needs the data will read it through its own contract.
 *
 * The state is read with `checkOpNoThrow` for Nivara's own package. The "unsafe" variants are
 * deprecated in API 36, and the non-throwing check reports the same mode without needing the
 * permission to be granted first. A missing service or a failed check becomes
 * [UsageAccessStatus.Unavailable] rather than an exception or a refusal.
 */
class AndroidUsageAccessRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UsageAccessRepository {

    override suspend fun status(): UsageAccessStatus = withContext(dispatcher) {
        nivaraRunCatching { readStatus() }.valueOrNull() ?: UsageAccessStatus.Unavailable
    }

    override suspend fun openSettings(): NivaraResult<Unit> = nivaraRunCatching {
        // Android's screen, opened as-is: no deep link that only some devices support, no
        // automation of the screen, and no attempt to change anything on the user's behalf.
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun readStatus(): UsageAccessStatus {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return UsageAccessStatus.Unavailable
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return usageAccessStatusForMode(mode)
    }
}
