package com.nivara.app.data.session

import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.security.AuthenticationSource
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricUnavailability
import com.nivara.app.domain.security.SessionState
import com.nivara.app.domain.security.SessionTimeoutPolicy
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
import com.nivara.app.testing.testSessionManager
import com.nivara.app.testing.testSessionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the session gate, against the real implementation.
 *
 * Only the clock and the timer's coroutine scope are test-supplied — the real
 * [InMemorySessionManager], the real [SessionTimeoutPolicy] and the real session value are what
 * these tests exercise. Nothing here sleeps: the wall clock is a value the test moves, and the
 * timer runs on the test's virtual scheduler.
 *
 * What the suite is really about is that a session can be opened by exactly one kind of fact — a
 * success — and can be ended by exactly two: the deadline, and an explicit lock. Everything else
 * that authentication can produce leaves the gate as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemorySessionManagerTest {

    private val time = MutableTimeProvider()

    /** Exactly one minute, so "just before" and "at" the expiry are easy to read. */
    private val sessionPolicy = testSessionPolicy()

    /** The manager under test, with the timer wired to this test's virtual scheduler. */
    private fun TestScope.manager(
        policy: SessionTimeoutPolicy = sessionPolicy,
    ): InMemorySessionManager = InMemorySessionManager(
        timeProvider = time,
        policy = policy,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
    )

    /** Moves the wall clock and the timer together, as wall time passing would. */
    private fun TestScope.advance(millis: Long) {
        time.advanceBy(millis)
        advanceTimeBy(millis)
        runCurrent()
    }

    // ------------------------------------------------------------------ a fresh gate

    @Test
    fun `a newly constructed manager starts unauthenticated`() = runTest {
        val manager = manager()

        assertEquals(SessionState.Unauthenticated, manager.currentState())
        assertEquals(SessionState.Unauthenticated, manager.state.value)
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `a second manager shares nothing with an authenticated one`() = runTest {
        val first = manager()
        first.establish(AuthenticationOutcome.Succeeded)
        assertTrue(first.isAuthenticated())

        // Nothing is written anywhere, so a new object — which is all a recreated process would
        // have — knows nothing about the session the first one holds.
        val second = manager()

        assertFalse(second.isAuthenticated())
        assertTrue(first.isAuthenticated())
    }

    // ------------------------------------------------------------------ establishment

    @Test
    fun `a successful primary authentication opens a session`() = runTest {
        val manager = manager()

        val session = manager.establish(AuthenticationOutcome.Succeeded)

        assertTrue(session is SessionState.Authenticated)
        val authenticated = session as SessionState.Authenticated
        assertEquals(AuthenticationSource.Primary, authenticated.source)
        assertEquals(time.nowMillis(), authenticated.startedAtMillis)
        assertEquals(
            time.nowMillis() + TEST_SESSION_TIMEOUT_MILLIS,
            authenticated.expiresAtMillis,
        )
        assertTrue(manager.isAuthenticated())
        assertEquals(session, manager.state.value)
    }

    @Test
    fun `a successful biometric authentication opens a session too`() = runTest {
        val manager = manager()

        val session = manager.establish(BiometricAuthenticationOutcome.Succeeded)

        assertTrue(session is SessionState.Authenticated)
        assertEquals(AuthenticationSource.Biometric, (session as SessionState.Authenticated).source)
        assertTrue(manager.isAuthenticated())
    }

    @Test
    fun `no primary outcome except success can open a session`() = runTest {
        val outcomes = listOf(
            AuthenticationOutcome.Failed(blockedForMillis = 0L),
            AuthenticationOutcome.Failed(blockedForMillis = 30_000L),
            AuthenticationOutcome.TemporarilyBlocked(retryAfterMillis = 5_000L),
            AuthenticationOutcome.NotConfigured,
            AuthenticationOutcome.InvalidConfiguration,
        )

        for (outcome in outcomes) {
            val manager = manager()

            assertEquals(
                "outcome $outcome must not open a session",
                SessionState.Unauthenticated,
                manager.establish(outcome),
            )
            assertFalse(manager.isAuthenticated())
        }
    }

    @Test
    fun `no biometric outcome except success can open a session`() = runTest {
        val outcomes = listOf(
            BiometricAuthenticationOutcome.Failed(attemptsRemaining = 4, blockedForMillis = 0L),
            BiometricAuthenticationOutcome.Cancelled,
            BiometricAuthenticationOutcome.TemporarilyBlocked(retryAfterMillis = 30_000L),
            BiometricAuthenticationOutcome.SystemBlocked(permanent = false),
            BiometricAuthenticationOutcome.SystemBlocked(permanent = true),
            BiometricAuthenticationOutcome.NotEnabled,
            BiometricAuthenticationOutcome.Invalidated,
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.NoHardware),
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.NotEnrolled),
        )

        for (outcome in outcomes) {
            val manager = manager()

            assertEquals(
                "outcome $outcome must not open a session",
                SessionState.Unauthenticated,
                manager.establish(outcome),
            )
            assertFalse(manager.isAuthenticated())
        }
    }

    @Test
    fun `a later failure leaves an open session alone`() = runTest {
        val manager = manager()
        val session = manager.establish(AuthenticationOutcome.Succeeded)

        // A mistyped credential while signed in is not a reason to sign the user out.
        manager.establish(AuthenticationOutcome.Failed(blockedForMillis = 0L))
        manager.establish(BiometricAuthenticationOutcome.Cancelled)

        assertEquals(session, manager.currentState())
        assertTrue(manager.isAuthenticated())
    }

    @Test
    fun `a second authentication replaces the session with a fresh deadline`() = runTest {
        val manager = manager()
        val first = manager.establish(AuthenticationOutcome.Succeeded) as SessionState.Authenticated

        advance(30_000L)
        val second = manager.establish(BiometricAuthenticationOutcome.Succeeded) as SessionState.Authenticated

        assertEquals(AuthenticationSource.Biometric, second.source)
        assertEquals(time.nowMillis(), second.startedAtMillis)
        assertEquals(
            "the new session gets the new deadline, not the remainder of the old one",
            first.expiresAtMillis + 30_000L,
            second.expiresAtMillis,
        )
    }

    // ------------------------------------------------------------------ validity

    @Test
    fun `a session is valid right up to its expiry`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)

        advance(TEST_SESSION_TIMEOUT_MILLIS - 1L)

        assertTrue(manager.isAuthenticated())
    }

    @Test
    fun `a session is invalid exactly at its expiry`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)

        advance(TEST_SESSION_TIMEOUT_MILLIS)

        assertFalse(manager.isAuthenticated())
        assertEquals(SessionState.Unauthenticated, manager.currentState())
    }

    @Test
    fun `checking an expired session ends it and keeps it ended`() = runTest {
        // A manager whose timer never runs, so the read is the only thing that can end the
        // session. This is the case a frozen or suspended process produces.
        val manager = testSessionManager(timeProvider = time, policy = sessionPolicy)
        manager.establish(AuthenticationOutcome.Succeeded)

        time.advanceBy(TEST_SESSION_TIMEOUT_MILLIS)

        assertFalse(manager.isAuthenticated())
        assertEquals(SessionState.Unauthenticated, manager.currentState())
        assertEquals(SessionState.Unauthenticated, manager.currentState())
        assertEquals(SessionState.Unauthenticated, manager.state.value)
    }

    @Test
    fun `the timeout follows the configured policy rather than a fixed number`() = runTest {
        val manager = manager(policy = SessionTimeoutPolicy(timeoutMillis = 1_000L))
        manager.establish(AuthenticationOutcome.Succeeded)

        advance(999L)
        assertTrue(manager.isAuthenticated())

        advance(1L)
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `the published state ends the session on its own when the timeout passes`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)

        // Nobody asks anything: the gate's own timer closes it, which is what keeps a screen that
        // is merely watching from showing protected content for a session that has ended.
        advance(TEST_SESSION_TIMEOUT_MILLIS)
        runCurrent()

        assertEquals(SessionState.Unauthenticated, manager.state.value)
    }

    @Test
    fun `a timer that wakes before the deadline leaves the session alone`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)

        // Virtual time moves; the wall clock does not. That is what a timer firing early — or a
        // device clock that was moved backwards — looks like, and it must not end a live session.
        advanceTimeBy(TEST_SESSION_TIMEOUT_MILLIS)
        runCurrent()

        assertTrue(manager.isAuthenticated())
        assertTrue(manager.state.value is SessionState.Authenticated)
    }

    // ------------------------------------------------------------------ Quick Lock

    @Test
    fun `Quick Lock ends a valid session immediately`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)

        val state = manager.lockNow()

        assertEquals(SessionState.Unauthenticated, state)
        assertEquals(SessionState.Unauthenticated, manager.state.value)
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `Quick Lock is idempotent`() = runTest {
        val manager = manager()
        manager.establish(BiometricAuthenticationOutcome.Succeeded)

        assertEquals(SessionState.Unauthenticated, manager.lockNow())
        assertEquals(SessionState.Unauthenticated, manager.lockNow())
        assertEquals(SessionState.Unauthenticated, manager.lockNow())
    }

    @Test
    fun `locking an application that was never unlocked is safe`() = runTest {
        val manager = manager()

        assertEquals(SessionState.Unauthenticated, manager.lockNow())
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `locking after the timeout changes nothing`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)
        advance(TEST_SESSION_TIMEOUT_MILLIS)

        assertEquals(SessionState.Unauthenticated, manager.lockNow())
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `a locked session stays locked even after its old deadline`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)
        manager.lockNow()

        // The timer belonging to the cancelled session must not be able to do anything later —
        // in particular it must not resurrect the session or disturb a new one.
        advance(TEST_SESSION_TIMEOUT_MILLIS * 3L)

        assertEquals(SessionState.Unauthenticated, manager.state.value)
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `authentication is required again after Quick Lock`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)
        manager.lockNow()

        // Nothing is reopened by looking: only a fresh success opens a session.
        assertFalse(manager.isAuthenticated())

        val reopened = manager.establish(AuthenticationOutcome.Succeeded)

        assertTrue(reopened is SessionState.Authenticated)
        assertTrue(manager.isAuthenticated())
    }

    @Test
    fun `a failure cannot reopen a session that was locked`() = runTest {
        val manager = manager()
        manager.establish(AuthenticationOutcome.Succeeded)
        manager.lockNow()

        // Every non-success outcome leaves the gate closed, so a rejected attempt that arrives
        // after a lock cannot slip the session open again.
        manager.establish(AuthenticationOutcome.Failed(blockedForMillis = 0L))
        manager.establish(BiometricAuthenticationOutcome.Cancelled)
        manager.establish(BiometricAuthenticationOutcome.NotEnabled)

        assertFalse(manager.isAuthenticated())
        assertEquals(SessionState.Unauthenticated, manager.state.value)
    }

    @Test
    fun `repeated expiry checks and locks leave the same state`() = runTest {
        val manager = manager()

        repeat(5) {
            assertEquals(SessionState.Unauthenticated, manager.currentState())
            assertEquals(SessionState.Unauthenticated, manager.lockNow())
            assertFalse(manager.isAuthenticated())
        }
    }
}
