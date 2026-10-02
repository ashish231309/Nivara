package com.nivara.app.data.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.app.inDefaultApplicationOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ApplicationRepository] built on the platform package manager.
 *
 * Three decisions are worth stating, because each one could have been made differently:
 *
 * - **Launcher entries only.** The query is the one Android's own launcher uses, so the result is
 *   the applications a person can actually open. Packages with no launcher entry point are never
 *   enumerated, which keeps the list meaningful and keeps Nivara from reading around the device.
 * - **The manifest, not a permission, is what makes the query work.** From API 30 the platform
 *   hides installed applications by default; the `<queries>` element matching `MAIN` + `LAUNCHER`
 *   is the narrow declaration that restores exactly this query. `QUERY_ALL_PACKAGES` is not used:
 *   it would expose every package on the device to answer a question about launcher entries.
 * - **Nothing is stored.** The list lives in memory for as long as the caller needs it; there is no
 *   cache, no file and no database behind this class.
 */
class AndroidApplicationRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApplicationRepository {

    override suspend fun installedApplications(): NivaraResult<List<InstalledApplication>> =
        withContext(dispatcher) {
            nivaraRunCatching { queryLauncherApplications() }
        }

    /**
     * Resolves every launcher entry into at most one [InstalledApplication] per package.
     *
     * An application can publish several launcher activities; they are one application and appear
     * once. A package whose label cannot be resolved, or that is uninstalled while this loop runs,
     * is skipped instead of failing the query, so one bad entry cannot hide the rest of the device.
     */
    private fun queryLauncherApplications(): List<InstalledApplication> {
        val packageManager = context.packageManager
        val ownPackage = context.packageName
        val byPackage = LinkedHashMap<String, InstalledApplication>()

        for (info in packageManager.launcherActivities()) {
            val packageName = info.activityInfo?.packageName
            if (packageName.isNullOrBlank() || packageName == ownPackage) continue
            if (byPackage.containsKey(packageName)) continue

            val application = nivaraRunCatching {
                InstalledApplication(
                    packageName = packageName,
                    label = resolveLabel(packageManager, info, packageName),
                )
            }.valueOrNull() ?: continue

            byPackage[packageName] = application
        }

        return byPackage.values.toList().inDefaultApplicationOrder()
    }

    /**
     * The label to show for an application: the launcher entry's own label, then the application's
     * label, then the package name.
     *
     * The last step is a fallback, not a name: if Android cannot produce either label, the package
     * name is still true and is better than an unnamed row. Nothing is ever invented for display.
     */
    private fun resolveLabel(
        packageManager: PackageManager,
        info: ResolveInfo,
        packageName: String,
    ): String {
        val activityLabel = nivaraRunCatching { info.loadLabel(packageManager).toString() }
            .valueOrNull()
        if (!activityLabel.isNullOrBlank()) return activityLabel

        val applicationLabel = nivaraRunCatching {
            packageManager.applicationInfo(packageName).loadLabel(packageManager).toString()
        }.valueOrNull()
        if (!applicationLabel.isNullOrBlank()) return applicationLabel

        return packageName
    }
}

/** The activities that answer the launcher intent: `MAIN` plus the `LAUNCHER` category. */
private fun PackageManager.launcherActivities(): List<ResolveInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, 0)
    }
}

/**
 * The application record for [packageName]. Throws `NameNotFoundException` if it is gone.
 *
 * Shared with the icon loader in this package: both need the same record and the same API-33 flag
 * handling, and one helper is better than two that could drift apart.
 */
internal fun PackageManager.applicationInfo(packageName: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }
