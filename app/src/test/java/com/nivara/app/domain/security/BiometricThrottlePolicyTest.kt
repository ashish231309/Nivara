package com.nivara.app.domain.security

import com.nivara.app.domain.credential.ThrottlePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Nivara's own biometric delay.
 *
 * The policy is a pure function of a failure count, so its whole schedule can be pinned down here.
 * What these tests are really asserting is the shape of the rule: a fixed allowance of free
 * failures, a flat interval after them, and no state that could keep a user out of their own data.
 *
 * Android's own lockout is not modelled here and cannot be: it belongs to the platform, it is
 * reported by the prompt, and Nivara never claims to override it.
 */
class BiometricThrottlePolicyTest {

    private val policy = BiometricThrottlePolicy.Default

    @Test
    fun `the first five failures cost nothing`() {
        assertEquals(0L, policy.delayMillisAfter(0))
        assertEquals(0L, policy.delayMillisAfter(1))
        assertEquals(0L, policy.delayMillisAfter(2))
        assertEquals(0L, policy.delayMillisAfter(3))
        assertEquals(0L, policy.delayMillisAfter(4))
    }

    @Test
    fun `the failure that uses up the allowance is the last free one`() {
        // Five failures are allowed; the attempt after the fifth is the first one that waits.
        assertEquals(
            BiometricThrottlePolicy.DEFAULT_FREE_ATTEMPTS,
            5,
        )
        assertEquals(30_000L, policy.delayMillisAfter(5))
    }

    @Test
    fun `every attempt after the allowance waits the same half minute`() {
        assertEquals(30_000L, policy.delayMillisAfter(6))
        assertEquals(30_000L, policy.delayMillisAfter(7))
        assertEquals(30_000L, policy.delayMillisAfter(50))
        assertEquals(30_000L, policy.delayMillisAfter(10_000))
    }

    @Test
    fun `the delay never grows into a lockout`() {
        // A flat interval is the whole point: the user waits, they are never locked out, and the
        // primary credential still works throughout.
        val delay = policy.delayMillisAfter(100_000)

        assertTrue("the delay must stay bounded", delay <= 30_000L)
        assertTrue("the delay must be positive once it applies", delay > 0L)
    }

    @Test
    fun `the policy is the same decision every time it is asked`() {
        val first = policy.delayMillisAfter(9)
        val second = policy.delayMillisAfter(9)

        assertEquals(first, second)
    }

    @Test
    fun `it is usable wherever the credential layer expects a throttle policy`() {
        val asThrottlePolicy: ThrottlePolicy = BiometricThrottlePolicy(freeAttempts = 1, retryDelayMillis = 1_000L)

        assertEquals(0L, asThrottlePolicy.delayMillisAfter(0))
        assertEquals(1_000L, asThrottlePolicy.delayMillisAfter(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a policy without free attempts is rejected`() {
        BiometricThrottlePolicy(freeAttempts = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a policy without a delay is rejected`() {
        BiometricThrottlePolicy(retryDelayMillis = 0L)
    }
}
