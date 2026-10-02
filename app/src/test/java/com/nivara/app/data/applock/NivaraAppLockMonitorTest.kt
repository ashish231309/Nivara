package com.nivara.app.data.applock

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.applock.AppLockDetectionPolicy
import com.nivara.app.domain.applock.AppLockFailure
import com.nivara.app.domain.applock.AppLockState
import com.nivara.app.domain.applock.DetectionUnavailability
import com.nivara.app.domain.applock.ForegroundApplication
import com.nivara.app.domain.applock.ForegroundApplicationDetector
import com.nivara.app.domain.applock.ProtectedApplication
import com.nivara.app.domain.applock.ProtectedApplicationRepository
import com.nivara.app.domain.applock.ProtectionDecision
import com.nivara.app.domain.applock.ProtectionDecisionEngine
import com.nivara.app.domain.applock.ProtectionEvent
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
import com.nivara.app.testing.testSessionManager
import com.nivara.app.domain.credential.AuthenticationOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the monitoring loop.
 *
 * The collaborators are stubs of their *interfaces*: nothing here asks Android anything, and no
 * test claims that a real device would report any of these applications as being in the foreground.
 * What is verified is the state machine around that answer — when detection runs, how a missing
 * prerequisite is reported, that a missing prerequisite is never mistaken for "nothing to protect",
 * and that the same requirement is not raised twice.
 *
 * The session half is the production manager with a clock the test moves, so expiry and Quick Lock
 * are exercised for real rather than simulated: a session that runs out is a session the monitor
 * sees closed on its next look, with nothing to invalidate inside the monitor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NivaraAppLockMonitorTest {

    private val policy = AppLockDetectionPolicy(
        pollIntervalMillis = AppLockDetectionPolicy.MINIMUM_POLL_INTERVAL_MILLIS,
        initialLookbackMillis = 1_000L,
    )
    private val time = MutableTimeProvider()
    private val sessionManager: SessionManager = testSessionManager(timeProvider = time)

    private val protected = ProtectedApplication(PROTECTED_PACKAGE)
    private val protectedApplications = setOf(protected)
    private val foreground = ForegroundApplication(PROTECTED_PACKAGE)

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `nothing runs before anything starts it`() = runTest {
        val detector = FakeDetector()
        val monitor = monitor(scope = backgroundScope, detector = detector)

        assertEquals(AppLockState.Stopped, monitor.state.value)
        assertEquals(0, detector.calls)
    }

    @Test
    fun `starting publishes the first observation`() = runTest {
        val monitor = monitor(scope = backgroundScope)

        monitor.start()
        runCurrent()

        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.AuthenticationRequired(protected)),
            monitor.state.value,
        )
    }

    @Test
    fun `starting twice leaves one loop`() = runTest {
        val detector = FakeDetector()
        val monitor = monitor(scope = backgroundScope, detector = detector)

        monitor.start()
        monitor.start()
        runCurrent()
        assertEquals(1, detector.calls)

        advanceOneInterval()

        assertEquals("a second start must not add a second loop", 2, detector.calls)
    }

    @Test
    fun `stopping returns the state and releases the loop`() = runTest {
        val detector = FakeDetector()
        val monitor = monitor(scope = backgroundScope, detector = detector)
        monitor.start()
        runCurrent()

        monitor.stop()

        assertEquals(AppLockState.Stopped, monitor.state.value)
        advanceOneInterval()
        val afterStop = detector.calls
        advanceOneInterval()
        assertEquals("a stopped monitor must not keep observing", afterStop, detector.calls)
    }

    @Test
    fun `an observation in flight cannot publish after a stop`() = runTest {
        val detector = FakeDetector().apply { gate = CompletableDeferred() }
        val monitor = monitor(scope = backgroundScope, detector = detector)
        monitor.start()
        runCurrent()

        monitor.stop()
        detector.gate?.complete(Unit)
        runCurrent()
        runCurrent()

        // The held call ignores cancellation, so it does finish and try to publish; only the
        // monitor's own guard keeps a stopped run from reporting a decision.
        assertEquals(AppLockState.Stopped, monitor.state.value)
    }

    @Test
    fun `restarting reports a requirement that is still true`() = runTest {
        val monitor = monitor(scope = backgroundScope)
        val events = collectEvents(monitor)
        monitor.start()
        runCurrent()
        assertEquals(1, events.size)

        monitor.stop()
        monitor.start()
        runCurrent()

        assertEquals("a restarted monitor treats what it finds as new", 2, events.size)
        assertEquals(ProtectionEvent.AuthenticationRequired(protected), events.last())
    }

    // ------------------------------------------------------------------ prerequisites

    @Test
    fun `a missing grant is unavailable, never an empty foreground`() = runTest {
        val detector = FakeDetector()
        val monitor = monitor(
            scope = backgroundScope,
            detector = detector,
            usageAccess = FakeUsageAccess(UsageAccessStatus.NotGranted),
        )

        monitor.start()
        runCurrent()

        assertEquals(AppLockState.Unavailable(DetectionUnavailability.UsageAccessNotGranted), monitor.state.value)
        assertEquals("without the grant there is nothing to observe", 0, detector.calls)
        assertNotEquals(AppLockState.Monitoring(null, ProtectionDecision.NoProtectionRequired), monitor.state.value)
    }

    @Test
    fun `a grant that cannot be read is unavailable`() = runTest {
        val monitor = monitor(
            scope = backgroundScope,
            usageAccess = FakeUsageAccess(UsageAccessStatus.Unavailable),
        )

        monitor.start()
        runCurrent()

        assertEquals(AppLockState.Unavailable(DetectionUnavailability.UsageAccessUnavailable), monitor.state.value)
    }

    @Test
    fun `an unreadable protected set is unavailable, never an empty set`() = runTest {
        val detector = FakeDetector()
        val monitor = monitor(
            scope = backgroundScope,
            detector = detector,
            applications = FakeProtectedApplications(failing = true),
        )

        monitor.start()
        runCurrent()

        assertEquals(
            AppLockState.Unavailable(DetectionUnavailability.ProtectedApplicationsUnreadable),
            monitor.state.value,
        )
        assertEquals("a failing set must stop the decision, not empty it", 0, detector.calls)
    }

    @Test
    fun `a platform that cannot report the foreground is unavailable`() = runTest {
        val monitor = monitor(
            scope = backgroundScope,
            detector = FakeDetector(failure = AppLockFailure.ForegroundUnavailable),
        )

        monitor.start()
        runCurrent()

        assertEquals(AppLockState.Unavailable(DetectionUnavailability.ForegroundUnavailable), monitor.state.value)
    }

    @Test
    fun `detection recovers on its own when the grant arrives`() = runTest {
        val usage = FakeUsageAccess(UsageAccessStatus.NotGranted)
        val monitor = monitor(scope = backgroundScope, usageAccess = usage)
        monitor.start()
        runCurrent()
        assertEquals(AppLockState.Unavailable(DetectionUnavailability.UsageAccessNotGranted), monitor.state.value)

        usage.status = UsageAccessStatus.Granted
        advanceOneInterval()

        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.AuthenticationRequired(protected)),
            monitor.state.value,
        )
    }

    @Test
    fun `nothing in the foreground needs no protection`() = runTest {
        val monitor = monitor(scope = backgroundScope, detector = FakeDetector(foreground = null))

        monitor.start()
        runCurrent()

        assertEquals(
            AppLockState.Monitoring(null, ProtectionDecision.NoProtectionRequired),
            monitor.state.value,
        )
    }

    @Test
    fun `Nivara in the foreground is never protected`() = runTest {
        val monitor = monitor(
            scope = backgroundScope,
            detector = FakeDetector(ForegroundApplication(NIVARA_PACKAGE)),
            applications = FakeProtectedApplications(setOf(ProtectedApplication(NIVARA_PACKAGE))),
        )

        monitor.start()
        runCurrent()

        assertEquals(
            AppLockState.Monitoring(
                ForegroundApplication(NIVARA_PACKAGE),
                ProtectionDecision.NoProtectionRequired,
            ),
            monitor.state.value,
        )
    }

    // ------------------------------------------------------------------ requirements

    @Test
    fun `staying in a protected application raises the requirement once`() = runTest {
        val monitor = monitor(scope = backgroundScope)
        val events = collectEvents(monitor)
        monitor.start()
        runCurrent()

        repeat(5) { advanceOneInterval() }

        assertEquals(1, events.size)
        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.AuthenticationRequired(protected)),
            monitor.state.value,
        )
    }

    @Test
    fun `leaving and returning raises the requirement again`() = runTest {
        val detector = FakeDetector()
        val monitor = monitor(scope = backgroundScope, detector = detector)
        val events = collectEvents(monitor)
        monitor.start()
        runCurrent()

        detector.foreground = ForegroundApplication("com.example.notes")
        advanceOneInterval()
        detector.foreground = foreground
        advanceOneInterval()

        assertEquals(2, events.size)
        assertEquals(ProtectionEvent.AuthenticationRequired(protected), events.last())
    }

    @Test
    fun `an open session settles the requirement`() = runTest {
        val monitor = monitor(scope = backgroundScope)
        val events = collectEvents(monitor)
        monitor.start()
        runCurrent()

        sessionManager.establish(AuthenticationOutcome.Succeeded)
        advanceOneInterval()

        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.NoProtectionRequired),
            monitor.state.value,
        )
        assertEquals(1, events.size)
    }

    @Test
    fun `an expired session requires authentication again`() = runTest {
        val monitor = monitor(scope = backgroundScope)
        val events = collectEvents(monitor)
        monitor.start()
        runCurrent()
        sessionManager.establish(AuthenticationOutcome.Succeeded)
        advanceOneInterval()
        assertEquals(1, events.size)

        time.advanceBy(TEST_SESSION_TIMEOUT_MILLIS)
        advanceOneInterval()

        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.AuthenticationRequired(protected)),
            monitor.state.value,
        )
        assertEquals(2, events.size)
    }

    @Test
    fun `Quick Lock requires authentication again`() = runTest {
        val monitor = monitor(scope = backgroundScope)
        val events = collectEvents(monitor)
        monitor.start()
        runCurrent()
        sessionManager.establish(AuthenticationOutcome.Succeeded)
        advanceOneInterval()
        assertEquals(1, events.size)

        sessionManager.lockNow()
        advanceOneInterval()

        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.AuthenticationRequired(protected)),
            monitor.state.value,
        )
        assertEquals(2, events.size)
    }

    @Test
    fun `the monitor holds no session of its own`() = runTest {
        // Nothing in the monitor can be unlocked: an authenticated session is read from the gate on
        // every turn, and ending it there ends it here.
        val monitor = monitor(scope = backgroundScope)
        monitor.start()
        runCurrent()
        sessionManager.establish(AuthenticationOutcome.Succeeded)
        advanceOneInterval()
        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.NoProtectionRequired),
            monitor.state.value,
        )

        sessionManager.lockNow()
        advanceOneInterval()

        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.AuthenticationRequired(protected)),
            monitor.state.value,
        )
    }

    @Test
    fun `an unprotected application never raises anything`() = runTest {
        val monitor = monitor(
            scope = backgroundScope,
            detector = FakeDetector(ForegroundApplication("com.example.notes")),
        )
        val events = collectEvents(monitor)

        monitor.start()
        runCurrent()
        repeat(3) { advanceOneInterval() }

        assertTrue(events.isEmpty())
        assertFalse(monitor.state.value is AppLockState.Unavailable)
    }

    @Test
    fun `protecting an application while it is in front takes effect without a restart`() = runTest {
        val applications = FakeProtectedApplications(emptySet())
        val monitor = monitor(scope = backgroundScope, applications = applications)
        val events = collectEvents(monitor)
        monitor.start()
        runCurrent()
        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.NoProtectionRequired),
            monitor.state.value,
        )

        applications.applications = protectedApplications
        advanceOneInterval()

        assertEquals(1, events.size)
        assertEquals(
            AppLockState.Monitoring(foreground, ProtectionDecision.AuthenticationRequired(protected)),
            monitor.state.value,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun monitor(
        scope: CoroutineScope,
        detector: FakeDetector = FakeDetector(),
        applications: ProtectedApplicationRepository = FakeProtectedApplications(protectedApplications),
        usageAccess: UsageAccessRepository = FakeUsageAccess(UsageAccessStatus.Granted),
        decisions: ProtectionDecisionEngine = ProtectionDecisionEngine(NIVARA_PACKAGE),
    ): NivaraAppLockMonitor = NivaraAppLockMonitor(
        detector = detector,
        protectedApplications = applications,
        usageAccess = usageAccess,
        sessionManager = sessionManager,
        decisions = decisions,
        policy = policy,
        scope = scope,
    )

    /** One poll interval, plus a moment, so exactly one more observation has happened. */
    private fun TestScope.advanceOneInterval() {
        testScheduler.advanceTimeBy(policy.pollIntervalMillis + 1)
        testScheduler.runCurrent()
    }

    /** Collects the requirement events the monitor raises from now on. */
    private fun TestScope.collectEvents(monitor: NivaraAppLockMonitor): List<ProtectionEvent> {
        val collected = mutableListOf<ProtectionEvent>()
        backgroundScope.launch { monitor.events.collect { event -> collected += event } }
        return collected
    }

    private class FakeDetector(
        var foreground: ForegroundApplication? = ForegroundApplication(PROTECTED_PACKAGE),
        private val failure: AppLockFailure? = null,
    ) : ForegroundApplicationDetector {

        var calls = 0

        /** When set, the next observation waits for it — used to hold a call in flight. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun foregroundApplication(): NivaraResult<ForegroundApplication?> {
            calls++
            // Deliberately uncancellable, so a test can hold an observation past a stop() and show
            // that a stopped monitor still refuses to publish it.
            gate?.let { held -> withContext(NonCancellable) { held.await() } }
            return failure?.let { error -> NivaraResult.Failure(error) } ?: NivaraResult.Success(foreground)
        }
    }

    private class FakeProtectedApplications(
        var applications: Set<ProtectedApplication> = emptySet(),
        private val failing: Boolean = false,
    ) : ProtectedApplicationRepository {

        override suspend fun protectedApplications(): NivaraResult<Set<ProtectedApplication>> =
            if (failing) {
                NivaraResult.Failure(AppLockFailure.ProtectedApplicationsUnreadable)
            } else {
                NivaraResult.Success(applications)
            }

        override suspend fun protect(application: ProtectedApplication): NivaraResult<Unit> =
            NivaraResult.Success(Unit)

        override suspend fun unprotect(application: ProtectedApplication): NivaraResult<Unit> =
            NivaraResult.Success(Unit)
    }

    private class FakeUsageAccess(var status: UsageAccessStatus) : UsageAccessRepository {

        override suspend fun status(): UsageAccessStatus = status

        override suspend fun openSettings(): NivaraResult<Unit> = NivaraResult.Success(Unit)
    }

    private companion object {
        const val NIVARA_PACKAGE = "com.nivara.test"
        const val PROTECTED_PACKAGE = "com.example.camera"
    }
}
