package com.nivara.app.data.permissions

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.domain.permissions.UsageAccessStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Usage Access capability check.
 *
 * These run on a device or emulator and consult the real application-operation state. No test here
 * grants anything: a grant is the user's decision, made in Android's settings screen, and no test
 * claims to have changed it. What is verified is that the mirrored mode numbers really are
 * `AppOpsManager`'s, that the check agrees with an independent read of the same operation, and that
 * it answers without throwing whatever the current grant is.
 *
 * The settings screen is deliberately not opened here: starting it would leave the application
 * under test and hand the device to another task. Its reachability is a manual check.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidUsageAccessRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = AndroidUsageAccessRepository(context)

    @Test
    fun the_mirrored_modes_match_the_platforms_own_values() {
        // The JVM mapping test trusts these two numbers; this is where the trust is checked.
        assertEquals(AppOpsManager.MODE_ALLOWED, USAGE_ACCESS_MODE_ALLOWED)
        assertEquals(AppOpsManager.MODE_ERRORED, USAGE_ACCESS_MODE_ERRORED)
    }

    @Test
    fun the_reported_status_is_one_of_the_three_answers() = runBlocking {
        val status = repository.status()

        assertTrue(
            "unexpected status $status",
            status == UsageAccessStatus.Granted ||
                status == UsageAccessStatus.NotGranted ||
                status == UsageAccessStatus.Unavailable,
        )
    }

    @Test
    fun the_status_comes_from_nivaras_own_operation_and_not_from_another_application() = runBlocking {
        val status = repository.status()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )

        assertEquals(usageAccessStatusForMode(mode), status)
    }

    @Test
    fun checking_the_status_repeatedly_gives_the_same_answer() = runBlocking {
        val first = repository.status()

        val second = repository.status()

        assertEquals(first, second)
    }
}
