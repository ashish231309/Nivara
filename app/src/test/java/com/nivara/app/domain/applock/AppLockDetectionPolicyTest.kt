package com.nivara.app.domain.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the detection policy.
 *
 * The numbers here are decisions, not constants: how quickly a protected application is noticed and
 * how much of the past a cold detector reads. They are asserted so that changing them is a
 * deliberate act rather than an edit somewhere in the polling code.
 */
class AppLockDetectionPolicyTest {

    @Test
    fun `the default policy checks once a second`() {
        assertEquals(1_000L, AppLockDetectionPolicy.Default.pollIntervalMillis)
        assertEquals(1_000L, AppLockDetectionPolicy.DEFAULT_POLL_INTERVAL_MILLIS)
    }

    @Test
    fun `a cold detector reads a bounded slice of the recent past`() {
        assertEquals(30_000L, AppLockDetectionPolicy.Default.initialLookbackMillis)
    }

    @Test
    fun `a poll interval below the floor is refused`() {
        val belowFloor = AppLockDetectionPolicy.MINIMUM_POLL_INTERVAL_MILLIS - 1

        assertThrows(IllegalArgumentException::class.java) {
            AppLockDetectionPolicy(pollIntervalMillis = belowFloor)
        }
    }

    @Test
    fun `the floor itself is accepted`() {
        val policy = AppLockDetectionPolicy(
            pollIntervalMillis = AppLockDetectionPolicy.MINIMUM_POLL_INTERVAL_MILLIS,
            initialLookbackMillis = AppLockDetectionPolicy.MINIMUM_POLL_INTERVAL_MILLIS,
        )

        assertEquals(AppLockDetectionPolicy.MINIMUM_POLL_INTERVAL_MILLIS, policy.pollIntervalMillis)
    }

    @Test
    fun `the initial window must cover at least one interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppLockDetectionPolicy(pollIntervalMillis = 1_000L, initialLookbackMillis = 999L)
        }
    }

    @Test
    fun `the floor is not a sub-second busy loop`() {
        assertTrue("the floor must be at least a quarter of a second", AppLockDetectionPolicy.MINIMUM_POLL_INTERVAL_MILLIS >= 250L)
    }
}
