package com.nivara.app.data.app

import android.content.Context
import android.content.Intent
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.fold
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.app.ApplicationLauncher
import com.nivara.app.domain.app.ApplicationLaunchFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts an application through the platform's own launcher facilities.
 *
 * ### How an application is started
 *
 * `PackageManager.getLaunchIntentForPackage` is the platform's answer to "how do I open this
 * application?", and it is the same answer the system launcher uses. The intent it returns is
 * resolved by the platform — Nivara never composes an intent of its own, never sends a component
 * name, and never accepts one from anywhere else. The package name that goes in is the one
 * discovery produced, so a row cannot be pointed at something other than the application it shows.
 *
 * ### What it does not do
 *
 * It changes nothing. No component is enabled or disabled, no package-manager record is written, no
 * other application's state is touched, and the started activity is left to the platform's task and
 * flag rules. Launching an application is not a privilege: it needs no permission, and Nivara holds
 * none for it.
 *
 * The work happens on the IO dispatcher, because resolving a launch intent and asking the system to
 * start it are binder calls, and they have no business running on the thread that draws the drawer.
 *
 * ### The two failures
 *
 * [ApplicationLaunchFailure.NotLaunchable] means the package has no launcher entry any more — it was
 * uninstalled, or it stopped publishing one — which is a fact about the catalogue, so the caller
 * re-reads. [ApplicationLaunchFailure.LaunchRefused] means the platform refused the start, which is
 * a fact about this moment rather than about the application. Neither message carries the package
 * name, so a failure object is safe to hold even if it is later reported somewhere unexpected.
 */
internal class AndroidApplicationLauncher(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApplicationLauncher {

    override suspend fun launch(packageName: String): NivaraResult<Unit> = withContext(dispatcher) {
        val intent = nivaraRunCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
            .valueOrNull()
            ?: return@withContext NivaraResult.Failure(ApplicationLaunchFailure.NotLaunchable)

        // A launch intent with no activity is not a launcher entry, whatever the platform returned.
        if (intent.resolveActivity(context.packageManager) == null) {
            return@withContext NivaraResult.Failure(ApplicationLaunchFailure.NotLaunchable)
        }

        nivaraRunCatching {
            // A new task, so the launched application does not join Nivara's task and pressing Back
            // in it returns to Nivara's launcher rather than to whatever Nivara was showing.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.fold(
            onSuccess = { NivaraResult.Success(Unit) },
            onFailure = { NivaraResult.Failure(ApplicationLaunchFailure.LaunchRefused) },
        )
    }
}
