package com.nivara.app.data.permissions

import com.nivara.app.domain.permissions.UsageAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Local JVM tests for the application-operation mode mapping.
 *
 * Only the translation is tested here: an Android framework is never asked anything, and no test
 * claims that a particular device reports a particular mode. Whether the mirrored mode numbers
 * match `AppOpsManager` on real hardware is asserted by the instrumented suite, which needs a
 * device.
 *
 * The rule under test is that a grant is the only thing reported as a grant: every other mode,
 * including ones this build has never seen, is treated as no grant, and the error mode is kept
 * separate because the capability cannot be used or changed in that state.
 */
class UsageAccessMappingTest {

    @Test
    fun `the allowed mode is the only grant`() {
        assertEquals(UsageAccessStatus.Granted, usageAccessStatusForMode(USAGE_ACCESS_MODE_ALLOWED))
    }

    @Test
    fun `the ignored mode is not a grant`() {
        assertEquals(UsageAccessStatus.NotGranted, usageAccessStatusForMode(1))
    }

    @Test
    fun `the default mode is not a grant`() {
        assertEquals(UsageAccessStatus.NotGranted, usageAccessStatusForMode(3))
    }

    @Test
    fun `the errored mode is reported as unavailable, not as a refusal`() {
        assertEquals(UsageAccessStatus.Unavailable, usageAccessStatusForMode(USAGE_ACCESS_MODE_ERRORED))
    }

    @Test
    fun `an unknown mode is never treated as a grant`() {
        val unknownModes = listOf(-1, 4, 42)

        unknownModes.forEach { mode ->
            val status = usageAccessStatusForMode(mode)

            assertEquals("mode $mode", UsageAccessStatus.NotGranted, status)
        }
    }

    @Test
    fun `no mode other than the allowed one produces a grant`() {
        (-2..8)
            .filter { mode -> mode != USAGE_ACCESS_MODE_ALLOWED }
            .forEach { mode ->
                assertNotEquals("mode $mode", UsageAccessStatus.Granted, usageAccessStatusForMode(mode))
            }
    }
}
