package com.nivara.app.data.security

import android.app.KeyguardManager
import android.content.Context
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.DeviceSecurityProvider

/**
 * [DeviceSecurityProvider] implementation backed by the platform `KeyguardManager`.
 *
 * Android 9 and newer can answer "is a screen lock configured" directly, so Nivara needs no
 * permission and no prompt for this information. Retrieval is a cheap in-process lookup, which
 * is why no background dispatcher is used; the method is nevertheless `suspend` because later
 * implementations of the same contract are expected to read from storage or the keystore.
 */
internal class AndroidDeviceSecurityProvider(private val context: Context) : DeviceSecurityProvider {

    override suspend fun isDeviceLockConfigured(): NivaraResult<Boolean> = nivaraRunCatching {
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            ?: return@nivaraRunCatching false
        keyguardManager.isDeviceSecure
    }
}
