package com.nivara.app.di

import android.content.Context
import com.nivara.app.data.security.AndroidDeviceSecurityProvider
import com.nivara.app.domain.security.DeviceSecurityProvider

/**
 * Application composition root.
 *
 * Interfaces are exposed here and implemented elsewhere, so the UI never depends on a
 * concrete platform implementation. New providers are added as later stages need them.
 */
interface AppContainer {
    val deviceSecurityProvider: DeviceSecurityProvider
}

/**
 * Default [AppContainer] backed by the application context.
 *
 * Implementations are created lazily: nothing is constructed until a screen actually asks
 * for it, which keeps cold start cheap.
 */
class DefaultAppContainer(context: Context) : AppContainer {

    private val applicationContext: Context = context.applicationContext

    override val deviceSecurityProvider: DeviceSecurityProvider by lazy {
        AndroidDeviceSecurityProvider(applicationContext)
    }
}
