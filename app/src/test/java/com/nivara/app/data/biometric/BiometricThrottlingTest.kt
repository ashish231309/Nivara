package com.nivara.app.data.biometric

import com.nivara.app.domain.credential.AttemptTracker
import com.nivara.app.domain.credential.ThrottleState
import com.nivara.app.domain.security.BiometricThrottlePolicy
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.testAttemptStore
import com.nivara.app.testing.testAttemptTracker
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for Nivara's biometric failure counter, using the real tracker and the real file store.
 *
 * Only the clock and the directory are test-supplied: a clock the test advances instead of
 * sleeping into, and a temporary folder instead of the application's private storage. The
 * counter, the schedule and the file format are the production ones.
 *
 * This is also where the separation requirement is proved rather than asserted in prose: the
 * biometric counter and the credential counter share an implementation and share nothing else —
 * not their file, not their policy, not their state.
 */
class BiometricThrottlingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val biometricFile: File get() = File(temporaryFolder.root, "biometric-attempts.nva")
    private val credentialFile: File get() = File(temporaryFolder.root, "credential-attempts.nva")

    private val time = MutableTimeProvider()

    // The tracker is built on demand rather than at construction: the temporary folder does not
    // exist until JUnit has applied its rule. Each access opens the same file, which is the point —
    // the state under test lives on disk, not in the tracker object.
    private val biometrics: AttemptTracker
        get() = testAttemptTracker(
            store = testAttemptStore(biometricFile),
            timeProvider = time,
            policy = BiometricThrottlePolicy.Default,
        )

    /** The credential's own counter: the default policy, and a different file. */
    private val credentials: AttemptTracker
        get() = testAttemptTracker(testAttemptStore(credentialFile), time)

    @Test
    fun `four failures leave the next attempt free`() = runTest {
        repeat(4) { biometrics.recordFailure() }

        assertEquals(0L, biometrics.remainingBlockMillis())
    }

    @Test
    fun `the fifth failure makes the next attempt wait half a minute`() = runTest {
        repeat(5) { biometrics.recordFailure() }

        assertEquals(30_000L, biometrics.remainingBlockMillis())
    }

    @Test
    fun `further failures keep waiting the same interval`() = runTest {
        repeat(6) { biometrics.recordFailure() }
        assertEquals(30_000L, biometrics.remainingBlockMillis())

        time.advanceBy(30_000L)
        biometrics.recordFailure()

        assertEquals(30_000L, biometrics.remainingBlockMillis())
    }

    @Test
    fun `the wait expires on its own`() = runTest {
        repeat(5) { biometrics.recordFailure() }
        assertEquals(30_000L, biometrics.remainingBlockMillis())

        time.advanceBy(30_000L)

        assertEquals(0L, biometrics.remainingBlockMillis())
    }

    @Test
    fun `a successful authentication forgets the failures and the wait`() = runTest {
        repeat(7) { biometrics.recordFailure() }

        biometrics.recordSuccess()

        assertEquals(0L, biometrics.remainingBlockMillis())
        assertEquals(ThrottleState.Clear, biometrics.currentState())

        // The free allowance starts over rather than resuming where it left off.
        repeat(4) { biometrics.recordFailure() }
        assertEquals(0L, biometrics.remainingBlockMillis())
    }

    @Test
    fun `the counter survives being reopened`() = runTest {
        repeat(5) { biometrics.recordFailure() }

        val reopened = testAttemptTracker(
            store = testAttemptStore(biometricFile),
            timeProvider = time,
            policy = BiometricThrottlePolicy.Default,
        )

        assertEquals(30_000L, reopened.remainingBlockMillis())
    }

    @Test
    fun `a biometric failure never reaches the credential's counter`() = runTest {
        repeat(5) { biometrics.recordFailure() }

        assertEquals(5, biometrics.currentState().consecutiveFailures)
        assertEquals(ThrottleState.Clear, credentials.currentState())

        // ...and the other way round: the credential's own failures leave biometrics alone.
        repeat(4) { credentials.recordFailure() }

        assertEquals(4, credentials.currentState().consecutiveFailures)
        assertEquals(5, biometrics.currentState().consecutiveFailures)
        assertEquals(30_000L, biometrics.remainingBlockMillis())
    }
}
