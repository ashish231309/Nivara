package com.nivara.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.nivara.app.di.AppContainer
import com.nivara.app.domain.security.BiometricPromptHost
import com.nivara.app.ui.NivaraApp
import com.nivara.app.ui.theme.NivaraTheme

/**
 * The single activity of Nivara.
 *
 * Compose owns the whole window, so there is no reason to spread screens across activities;
 * keeping one activity also means sensitive state stays inside one process and one task.
 *
 * The activity is also the host Android attaches its biometric prompt to. Nivara never draws a
 * prompt of its own, so the only thing that happens here is registering this activity with the
 * biometric authenticator while it is alive, and releasing it when it goes away. `FragmentActivity`
 * is required because that is what Android's prompt is built on.
 */
class MainActivity : FragmentActivity(), BiometricPromptHost {

    private val container: AppContainer get() = (application as NivaraApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars; the app bar and screens apply their own insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        container.biometricAuthenticator.attachHost(this)

        setContent {
            NivaraTheme {
                NivaraApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user left protection on, a restart or a platform restriction may have ended the
        // service; returning to Nivara is a foreground path, so restoring it here is always
        // permitted and makes a refused boot start a delay rather than a loss.
        if (container.protectionRunStateStore.isEnabled()) {
            container.appLockProtectionRunner.start()
        }
    }

    override fun onDestroy() {
        // The authenticator keeps the host only while this activity is alive, so shutting down or
        // being recreated cannot leave a stale screen behind.
        container.biometricAuthenticator.detachHost(this)
        super.onDestroy()
    }
}
