package com.nivara.app.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the timeout rule.
 *
 * The policy is a pure function of two instants, so its whole behaviour can be pinned down here,
 * without a clock, a device or a session: when a session that started at a given moment ends, and
 * which side of that instant a check falls on.
 *
 * The boundary is the interesting part. "Has the session expired?" must have exactly one answer at
 * every instant, so the interval is half-open: valid strictly before the expiry, invalid at it.
 */
class SessionTimeoutPolicyTest {

    private val policy = SessionTimeoutPolicy(timeoutMillis = 60_000L)
    private val start = 1_700_000_000_000L

    @Test
    fun `a session expires one timeout after it starts`() {
        assertEquals(start + 60_000L, policy.expiryFor(start))
    }

    @Test
    fun `a session is valid right after it starts`() {
        assertTrue(policy.isValidAt(startedAtMillis = start, nowMillis = start))
    }

    @Test
    fun `a session is valid one millisecond before it expires`() {
        assertTrue(policy.isValidAt(startedAtMillis = start, nowMillis = start + 59_999L))
    }

    @Test
    fun `a session is invalid exactly at its expiry`() {
        assertFalse(policy.isValidAt(startedAtMillis = start, nowMillis = policy.expiryFor(start)))
    }

    @Test
    fun `a session stays invalid afterwards`() {
        assertFalse(policy.isValidAt(startedAtMillis = start, nowMillis = start + 60_001L))
        assertFalse(policy.isValidAt(startedAtMillis = start, nowMillis = start + 86_400_000L))
    }

    @Test
    fun `the expiry is computed the same way every time`() {
        assertEquals(policy.expiryFor(start), policy.expiryFor(start))
        assertEquals(60_000L, policy.expiryFor(start) - start)
    }

    @Test
    fun `the default policy is five minutes`() {
        assertEquals(5L * 60L * 1_000L, SessionTimeoutPolicy.DEFAULT_TIMEOUT_MILLIS)
        assertEquals(
            SessionTimeoutPolicy.DEFAULT_TIMEOUT_MILLIS,
            SessionTimeoutPolicy.Default.timeoutMillis,
        )
    }

    @Test
    fun `the policy is configurable through a value rather than through callers`() {
        val short = SessionTimeoutPolicy(timeoutMillis = 1L)

        assertTrue(short.isValidAt(startedAtMillis = start, nowMillis = start))
        assertFalse(short.isValidAt(startedAtMillis = start, nowMillis = start + 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a timeout of zero is rejected`() {
        SessionTimeoutPolicy(timeoutMillis = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative timeout is rejected`() {
        SessionTimeoutPolicy(timeoutMillis = -1L)
    }
}
