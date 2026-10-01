package com.nivara.app.data.applock

import android.content.ComponentName
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nivara.app.MainActivity
import com.nivara.app.NivaraApplication
import com.nivara.app.domain.applock.AppLockState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the component that owns detection.
 *
 * These run against the real service and the real application container, and they assert the
 * lifecycle contract rather than detection: starting the service starts the single monitor, and the
 * service going away returns the monitor to stopped. Whether the monitor then *sees* anything is
 * not asserted here — that depends on the Usage Access grant and on someone using the device, and
 * this file claims nothing about it.
 *
 * The activity rule is not decoration: from Android 8 a background application may not start a
 * background service, so the service is started while Nivara is visible — exactly how the
 * application starts it.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AppLockDetectionServiceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = composeRule.activity
    private val monitor get() = (context.application as NivaraApplication).container.appLockMonitor

    @Test
    fun starting_the_service_starts_the_shared_monitor_and_stopping_it_returns_it_to_stopped() = runBlocking {
        AppLockDetectionService.start(context)

        withTimeout(TIMEOUT_MILLIS) { monitor.state.first { state -> state != AppLockState.Stopped } }

        AppLockDetectionService.stop(context)

        val stopped = withTimeout(TIMEOUT_MILLIS) { monitor.state.first { state -> state == AppLockState.Stopped } }
        assertEquals(AppLockState.Stopped, stopped)
    }

    @Test
    fun the_service_is_private_to_nivara() {
        assertFalse("nothing outside Nivara may start or stop detection", serviceInfo().exported)
    }

    @Test
    fun the_service_is_not_a_foreground_service() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        assertEquals(
            "detection runs without a foreground service, so it declares no service type",
            0,
            serviceInfo().foregroundServiceType,
        )
    }

    /** The declared service entry, read from the merged manifest on this device. */
    @Suppress("DEPRECATION")
    private fun serviceInfo() = context.packageManager.getServiceInfo(
        ComponentName(context.packageName, AppLockDetectionService::class.java.name),
        0,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
