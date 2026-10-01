package com.nivara.app.data.permissions

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.domain.permissions.OverlayCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the overlay capability check.
 *
 * These consult the real application-operation state on a device or emulator. No test grants
 * anything: the overlay grant is the user's decision, made in Android's own settings screen, and a
 * test that could change it would prove nothing about the state a real user is in.
 *
 * The settings screen is deliberately not opened here — starting it would leave the application
 * under test and hand the device to another task. Its reachability is a manual check, and the
 * repository's failure path is exercised on the JVM through the seam instead.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidOverlayCapabilityRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = AndroidOverlayCapabilityRepository(context)

    @Test
    fun the_reported_capability_is_one_of_the_three_answers() = runBlocking {
        val capability = repository.status()

        assertTrue(
            "unexpected capability $capability",
            capability == OverlayCapability.Granted ||
                capability == OverlayCapability.NotGranted ||
                capability == OverlayCapability.Unavailable,
        )
    }

    @Test
    fun the_capability_agrees_with_the_platforms_own_check() = runBlocking {
        // The same call the repository makes, read independently: an unreadable answer is
        // Unavailable here too, because that is what the platform itself said.
        val expected = try {
            if (Settings.canDrawOverlays(context)) OverlayCapability.Granted else OverlayCapability.NotGranted
        } catch (unreadable: Exception) {
            OverlayCapability.Unavailable
        }

        assertEquals(expected, repository.status())
    }

    @Test
    fun checking_the_capability_repeatedly_gives_the_same_answer() = runBlocking {
        val first = repository.status()

        val second = repository.status()

        assertEquals(first, second)
    }
}
