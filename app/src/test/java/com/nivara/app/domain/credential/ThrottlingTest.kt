package com.nivara.app.domain.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the delay schedule and the state the tracker persists.
 *
 * The schedule is a pure function of the failure count, which is why it can be pinned down here:
 * no clock, no storage and no coroutines are involved in deciding how long to wait. That is also
 * what makes it replaceable when the biometric lockout policy arrives in a later stage.
 */
class ThrottlingTest {

    private val policy = ExponentialThrottlePolicy.Default

    @Test
    fun `the first attempts are free`() {
        assertEquals(0L, policy.delayMillisAfter(0))
        assertEquals(0L, policy.delayMillisAfter(1))
        assertEquals(0L, policy.delayMillisAfter(2))
    }

    @Test
    fun `the delay grows from the third failure`() {
        assertEquals(5_000L, policy.delayMillisAfter(3))
        assertEquals(10_000L, policy.delayMillisAfter(4))
        assertEquals(20_000L, policy.delayMillisAfter(5))
        assertEquals(40_000L, policy.delayMillisAfter(6))
        assertEquals(80_000L, policy.delayMillisAfter(7))
    }

    @Test
    fun `the delay is capped and never becomes permanent`() {
        // Doubling from 5 s: 5, 10, 20, 40, 80, 160, and the next step would be 320 s.
        assertEquals(160_000L, policy.delayMillisAfter(8))
        assertEquals(300_000L, policy.delayMillisAfter(9))
        assertEquals(300_000L, policy.delayMillisAfter(1_000))
        assertEquals(300_000L, policy.delayMillisAfter(Int.MAX_VALUE))
    }

    @Test
    fun `a custom policy can be stricter`() {
        val strict = ExponentialThrottlePolicy(freeAttempts = 0, baseDelayMillis = 1_000L, maximumDelayMillis = 4_000L)

        assertEquals(1_000L, strict.delayMillisAfter(1))
        assertEquals(4_000L, strict.delayMillisAfter(4))
        assertEquals(4_000L, strict.delayMillisAfter(50))
    }

    @Test
    fun `a state with no block allows the next attempt`() {
        assertFalse(ThrottleState.Clear.isBlockedForTest(nowMillis = 1_000L))
        assertEquals(0L, ThrottleState.Clear.remainingBlockMillis(nowMillis = 1_000L))
    }

    @Test
    fun `remaining time counts down to zero`() {
        val state = ThrottleState(consecutiveFailures = 3, blockedUntilMillis = 10_000L)

        assertEquals(10_000L, state.remainingBlockMillis(nowMillis = 0L))
        assertEquals(4_000L, state.remainingBlockMillis(nowMillis = 6_000L))
        assertEquals(0L, state.remainingBlockMillis(nowMillis = 10_000L))
        assertEquals(0L, state.remainingBlockMillis(nowMillis = 99_000L))
        assertTrue(state.isBlockedForTest(nowMillis = 6_000L))
    }

    /** Reads the block state the way the tracker does, without exposing a clock in the type. */
    private fun ThrottleState.isBlockedForTest(nowMillis: Long): Boolean = remainingBlockMillis(nowMillis) > 0L
}
