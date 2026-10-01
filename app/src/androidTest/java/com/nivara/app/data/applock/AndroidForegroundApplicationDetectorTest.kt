package com.nivara.app.data.applock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.credential.SystemTimeProvider
import com.nivara.app.domain.applock.AppLockDetectionPolicy
import com.nivara.app.domain.applock.ForegroundApplicationDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for foreground detection.
 *
 * Only two things can honestly be asserted here without a person driving the phone:
 *
 * - **The mirrored event numbers are the platform's.** The mapping tests on the JVM trust two
 *   integers; this is where that trust is checked, against `UsageEvents.Event` itself.
 * - **The detector answers, repeatedly, without throwing.** Whether the answer names the
 *   application a real user just opened is not something a test can arrange: it would have to bring
 *   another application to the foreground and observe what Android reported, which is a manual
 *   check.
 *
 * A device without the Usage Access grant returns an empty event window, which is a successful
 * "nothing known" answer rather than a failure — the grant is checked by the monitor, not here. No
 * test in this file claims that detection works; it claims only what it observes.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidForegroundApplicationDetectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val detector: ForegroundApplicationDetector = AndroidForegroundApplicationDetector(
        context = context,
        timeProvider = SystemTimeProvider(),
        policy = AppLockDetectionPolicy.Default,
    )

    @Test
    fun the_mirrored_event_numbers_are_the_platforms_own() {
        @Suppress("DEPRECATION")
        val resumed = UsageEvents.Event.MOVE_TO_FOREGROUND
        @Suppress("DEPRECATION")
        val paused = UsageEvents.Event.MOVE_TO_BACKGROUND

        assertEquals(resumed, USAGE_EVENT_ACTIVITY_RESUMED)
        assertEquals(paused, USAGE_EVENT_ACTIVITY_PAUSED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29 renamed the two events; the numbers stayed the same, which is what makes one
            // mapping correct on every supported version.
            assertEquals(UsageEvents.Event.ACTIVITY_RESUMED, USAGE_EVENT_ACTIVITY_RESUMED)
            assertEquals(UsageEvents.Event.ACTIVITY_PAUSED, USAGE_EVENT_ACTIVITY_PAUSED)
        }
        assertTrue(
            "the two transitions must not share a number",
            USAGE_EVENT_ACTIVITY_RESUMED != USAGE_EVENT_ACTIVITY_PAUSED,
        )
    }

    @Test
    fun the_usage_services_are_reachable_on_this_device() {
        val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE)

        assertTrue("this device exposes no usage statistics service", usageManager is UsageStatsManager)
    }

    @Test
    fun observing_the_foreground_repeats_the_same_answer_without_throwing() = runBlocking {
        val first = detector.foregroundApplication()
        val second = detector.foregroundApplication()

        assertTrue("the first observation failed: $first", first.isSuccess)
        assertTrue("the second observation failed: $second", second.isSuccess)
        assertEquals(first.valueOrNull(), second.valueOrNull())
    }

    @Test
    fun an_observed_application_is_a_usable_package_name() = runBlocking {
        val result = detector.foregroundApplication()

        assertTrue("the observation failed: $result", result.isSuccess)
        val application = result.valueOrNull()
        if (application != null) {
            assertTrue("an observed application had no package name", application.packageName.isNotBlank())
            assertTrue(
                "an observed application had a name that is not a package name",
                application.packageName.none { character -> character.isWhitespace() },
            )
        }
    }
}
