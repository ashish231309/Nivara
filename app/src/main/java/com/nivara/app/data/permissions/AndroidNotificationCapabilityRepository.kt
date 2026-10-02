package com.nivara.app.data.permissions

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.permissions.NotificationCapability
import com.nivara.app.domain.permissions.NotificationCapabilityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [NotificationCapabilityRepository] built on the platform's permission state.
 *
 * Devices older than Android 13 have no notification permission at all — notifications always
 * show — which is reported as [NotificationCapability.NotRequestable] so the onboarding screen
 * offers nothing that does not exist. A failed check becomes [NotificationCapability.Unavailable]
 * rather than an exception or a refusal.
 */
class AndroidNotificationCapabilityRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NotificationCapabilityRepository {

    override suspend fun status(): NotificationCapability = withContext(dispatcher) {
        if (Build.VERSION.SDK_INT < 33) {
            NotificationCapability.NotRequestable
        } else {
            nivaraRunCatching {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }.valueOrNull()?.let { granted ->
                if (granted) NotificationCapability.Granted else NotificationCapability.NotGranted
            } ?: NotificationCapability.Unavailable
        }
    }
}
