package com.nivara.app.data.session

import com.nivara.app.domain.credential.AttemptTracker
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.security.AuthenticationSource
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricThrottlePolicy
import com.nivara.app.domain.security.SessionState
import com.nivara.app.domain.security.SessionTimeoutPolicy
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.testAttemptStore
import com.nivara.app.testing.testAttemptTracker
import com.nivara.app.testing.testSessionManager
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests that the session layer is separate from both throttling systems.
 *
 * The strongest thing that can be said about "a session must not touch the counters" is that the
 * session manager cannot reach them, and that is a property of its constructor: it takes a clock,
 * a timeout and a coroutine scope, and nothing else. These tests prove the consequence rather than
 * the shape — the real trackers are given failures, the real session manager is then used to
 * authenticate, time out and lock, and the counters are read back.
 *
 * The counters here are the production ones: the real [`PersistedAttemptTracker`] over the real
 * file store, with a clock the test moves by hand. Only the directory and the two files are
 * test-supplied.
 */
class SessionThrottlingSeparationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val time = MutableTimeProvider()

    private val policy = SessionTimeoutPolicy(timeoutMillis = 60_000L)

    private val credentials: AttemptTracker
        get() = testAttemptTracker(
            store = testAttemptStore(File(temporaryFolder.root, "credential-attempts.nva")),
            timeProvider = time,
        )

    private val biometrics: AttemptTracker
        get() = testAttemptTracker(
            store = testAttemptStore(File(temporaryFolder.root, "biometric-attempts.nva")),
            timeProvider = time,
            policy = BiometricThrottlePolicy.Default,
        )

    /** Both counters, each read from the file the application would use. */
    private fun bothCounters(): Pair<AttemptTracker, AttemptTracker> = credentials to biometrics

    @Test
    fun `locking the session does not reset the credential counter`() = runTest {
        val (credentials, _) = bothCounters()
        repeat(3) { credentials.recordFailure() }
        val before = credentials.currentState()

        val manager = testSessionManager(time, policy)
        manager.establish(AuthenticationOutcome.Succeeded)
        manager.lockNow()

        assertEquals(before, credentials.currentState())
        assertEquals(3, credentials.currentState().consecutiveFailures)
    }

    @Test
    fun `the session timing out does not reset the credential counter`() = runTest {
        val (credentials, _) = bothCounters()
        repeat(4) { credentials.recordFailure() }
        val before = credentials.currentState()

        val manager = testSessionManager(time, policy)
        manager.establish(AuthenticationOutcome.Succeeded)
        time.advanceBy(60_000L)
        assertFalse(manager.isAuthenticated())

        assertEquals(before, credentials.currentState())
    }

    @Test
    fun `locking the session does not reset the biometric counter`() = runTest {
        val (_, biometrics) = bothCounters()
        repeat(6) { biometrics.recordFailure() }
        val before = biometrics.currentState()
        assertTrue("the fixture should be past the free allowance", before.blockedUntilMillis > 0L)

        val manager = testSessionManager(time, policy)
        manager.establish(BiometricAuthenticationOutcome.Succeeded)
        manager.lockNow()

        assertEquals(before, biometrics.currentState())
        assertTrue(biometrics.remainingBlockMillis() > 0L)
    }

    @Test
    fun `the session timing out does not reset the biometric counter`() = runTest {
        val (_, biometrics) = bothCounters()
        repeat(6) { biometrics.recordFailure() }
        val before = biometrics.currentState()

        val manager = testSessionManager(time, policy)
        manager.establish(BiometricAuthenticationOutcome.Succeeded)
        time.advanceBy(60_000L)
        assertEquals(SessionState.Unauthenticated, manager.currentState())

        assertEquals(before, biometrics.currentState())
    }

    @Test
    fun `opening a session does not reset either counter`() = runTest {
        val (credentials, biometrics) = bothCounters()
        repeat(3) { credentials.recordFailure() }
        repeat(6) { biometrics.recordFailure() }
        val credentialState = credentials.currentState()
        val biometricState = biometrics.currentState()

        // The session layer is handed a success and opens a session. Whatever a real
        // authentication does to its *own* counter happens inside the credential manager; the
        // session must contribute nothing to either.
        val manager = testSessionManager(time, policy)
        val session = manager.establish(AuthenticationOutcome.Succeeded)

        assertTrue(session is SessionState.Authenticated)
        assertEquals(credentialState, credentials.currentState())
        assertEquals(biometricState, biometrics.currentState())
    }

    @Test
    fun `a biometric failure does not reach the credential counter`() = runTest {
        val (credentials, biometrics) = bothCounters()
        repeat(3) { credentials.recordFailure() }
        val before = credentials.currentState()

        // Five rejected biometrics, and a gate that saw them: the credential's own state is
        // exactly where it was — count and delay window both.
        val manager = testSessionManager(time, policy)
        repeat(5) {
            manager.establish(
                BiometricAuthenticationOutcome.Failed(attemptsRemaining = 4, blockedForMillis = 0L),
            )
            biometrics.recordFailure()
        }

        assertEquals(before, credentials.currentState())
        assertEquals(5, biometrics.currentState().consecutiveFailures)
    }

    @Test
    fun `a credential failure does not reach the biometric counter`() = runTest {
        val (credentials, biometrics) = bothCounters()

        val manager = testSessionManager(time, policy)
        manager.establish(AuthenticationOutcome.Failed(blockedForMillis = 30_000L))
        repeat(2) { credentials.recordFailure() }

        assertEquals(0, biometrics.currentState().consecutiveFailures)
        assertEquals(2, credentials.currentState().consecutiveFailures)
    }

    @Test
    fun `a locked session can be reopened while both counters are blocked`() = runTest {
        val (credentials, biometrics) = bothCounters()
        repeat(6) { credentials.recordFailure() }
        repeat(6) { biometrics.recordFailure() }

        // Locking is not a failure and blocking is not a lock: a session can still be opened by a
        // fresh success, because neither counter is a session concept.
        val manager = testSessionManager(time, policy)
        val reopened = manager.establish(AuthenticationOutcome.Succeeded)

        assertTrue(reopened is SessionState.Authenticated)
        assertEquals(AuthenticationSource.Primary, (reopened as SessionState.Authenticated).source)
        assertEquals(6, credentials.currentState().consecutiveFailures)
        assertEquals(6, biometrics.currentState().consecutiveFailures)
    }
}
