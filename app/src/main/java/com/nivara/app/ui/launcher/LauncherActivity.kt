package com.nivara.app.ui.launcher

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.nivara.app.NivaraApplication
import com.nivara.app.di.AppContainer
import com.nivara.app.domain.security.BiometricPromptHost
import com.nivara.app.ui.NivaraApp
import com.nivara.app.ui.navigation.NivaraDestination
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Nivara as an Android Home application.
 *
 * ### Why this is a second activity
 *
 * `MainActivity` is the application's own entry point: the task the user opens from whichever
 * launcher they use. This one *is* the Home application, and Android starts it with an intent that
 * carries the Home category. The two cannot be the same component — a Home activity that Android can
 * start has to be exported, and the application's ordinary entry point has no reason to be — so the
 * exported surface is kept to exactly one component with exactly one job.
 *
 * ### What it is exported for
 *
 * Only to answer `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT`, which is how a Home
 * application is selected and started. There is no other action, no `Intent` extra and no data URI:
 * a caller able to start this activity can do only what pressing Home does, which is show Nivara's
 * launcher. See `docs/launcher/README.md` for what that surface does and does not expose.
 *
 * ### What it does not do
 *
 * It does not make itself the default Home application, does not change any system setting, and does
 * not assume it will stay selected: it is an ordinary activity that happens to answer the Home
 * intent, and if the user picks a different Home application this one is simply not started. The
 * user selects Nivara through Android's own Home settings.
 *
 * ### Lifecycle
 *
 * Nothing is persisted here and nothing is restored here. The session the launcher reads lives in
 * memory behind the shared `SessionManager`, so a recreated process comes back unauthenticated with
 * no reveal to clear, and a resumed launcher re-reads the device and the stored hidden set rather
 * than trusting what it drew last time.
 */
class LauncherActivity : FragmentActivity(), BiometricPromptHost {

    private val container: AppContainer get() = (application as NivaraApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars, as the application's own activity does.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The launcher sends the user to the existing credential screen; the prompt that screen uses
        // is Android's own, and needs the activity it is shown over to be registered here.
        container.biometricAuthenticator.attachHost(this)

        setContent {
            NivaraTheme {
                // The same navigation graph as the rest of the application, entered at the launcher:
                // Nivara's settings are the existing screens, reached through the existing graph
                // rather than through a second copy of them.
                NivaraApp(startDestination = NivaraDestination.Launcher.route)
            }
        }
    }

    override fun onDestroy() {
        container.biometricAuthenticator.detachHost(this)
        super.onDestroy()
    }
}
