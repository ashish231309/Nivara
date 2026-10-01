package com.nivara.app.domain.applock

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricPromptHost
import com.nivara.app.domain.security.BiometricState
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
import com.nivara.app.testing.testSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the App Lock presentation rules.
 *
 * Everything here is a rule that must hold without a device, because everything here is a rule about
 * *decisions*: when a surface belongs on screen, which request an authentication result may be used
 * for, and what the existing session gate is told. The collaborators are stubs of their interfaces —
 * the real credential verification and the real biometric prompt belong to the layers that own them
 * and are tested there — except the session gate, which is the production manager with a clock this
 * suite moves, so expiry and Quick Lock are exercised for real.
 *
 * Nothing in this file claims that a window appeared, that an overlay permission was granted on a
 * device, or that a prompt was shown. Those are device questions, and they are declared as
 * unverified.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockOverlayPresenterTest {

    private val time = MutableTimeProvider()

    /**
     * The monitor a test gets when it does not build one of its own.
     *
     * JUnit creates a new instance of this class for every test, so each test starts with a fresh
     * monitor and no state leaks between them.
     */
    private val defaultMonitor = FakeMonitor()
    private val sessionManager: SessionManager = testSessionManager(timeProvider = time)
    private val protectedApplication = ProtectedApplication(PROTECTED_PACKAGE)
    private val nivara = "com.nivara.test"

    // ------------------------------------------------------------------ presentation

    @Test
    fun `nothing is presented before detection says something`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        runCurrent()

        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
    }

    @Test
    fun `a protected application in front without a session needs the surface`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        requireProtection()
        runCurrent()

        val state = presenter.state.value
        assertTrue(state is AppLockOverlayState.Required)
        state as AppLockOverlayState.Required
        assertEquals(protectedApplication, state.request.application)
        assertEquals(AppLockPhase.AwaitingCredential, state.phase)
        assertEquals(PrimaryCredentialType.Pin, state.credential)
    }

    @Test
    fun `an unprotected application never needs the surface`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)

        presenter.start()
        runCurrent()
        monitor.observe(ForegroundApplication("com.example.notes"))
        runCurrent()

        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
    }

    @Test
    fun `a valid session prevents the surface even when detection requires it`() = runTest {
        sessionManager.establish(AuthenticationOutcome.Succeeded)
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        requireProtection()
        runCurrent()

        assertEquals(
            "an already authenticated session must not cover the screen again",
            AppLockOverlayState.Idle,
            presenter.state.value,
        )
    }

    @Test
    fun `a repeated requirement is one request, not two`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)

        presenter.start()
        requireProtection(monitor)
        runCurrent()
        val first = (presenter.state.value as AppLockOverlayState.Required).request

        // The same application, the same requirement, announced again: a repeated event, a repeated
        // observation and a lifecycle poke all look like this.
        repeat(3) {
            reannounce(monitor)
            runCurrent()
        }

        val second = (presenter.state.value as AppLockOverlayState.Required).request
        assertEquals("the same occasion must stay one request", first, second)
    }

    @Test
    fun `leaving the protected application removes the requirement`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)

        presenter.start()
        requireProtection(monitor)
        runCurrent()
        assertTrue(presenter.state.value is AppLockOverlayState.Required)

        monitor.observe(ForegroundApplication("com.example.notes"))
        runCurrent()

        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
    }

    @Test
    fun `switching to another protected application replaces the request`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)
        val other = ProtectedApplication("com.example.vault")

        presenter.start()
        requireProtection(monitor)
        runCurrent()
        val first = (presenter.state.value as AppLockOverlayState.Required).request

        observeForeground(monitor, other)
        runCurrent()

        val second = (presenter.state.value as AppLockOverlayState.Required).request
        assertEquals(other, second.application)
        assertTrue("a new occasion needs a new identity", second.id != first.id)
    }

    @Test
    fun `detection going blind does not remove a surface that is already up`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)

        presenter.start()
        requireProtection(monitor)
        runCurrent()

        monitor.becomeBlind(DetectionUnavailability.UsageAccessNotGranted)
        runCurrent()

        assertTrue(
            "Nivara cannot see, so it must not read as 'everything is fine'",
            presenter.state.value is AppLockOverlayState.Required,
        )
    }

    @Test
    fun `detection going blind does not conjure a surface either`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)

        presenter.start()
        monitor.becomeBlind(DetectionUnavailability.ForegroundUnavailable)
        runCurrent()

        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
    }

    @Test
    fun `stopping detection removes the requirement`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)

        presenter.start()
        requireProtection(monitor)
        runCurrent()

        monitor.becomeStopped()
        runCurrent()

        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
    }

    @Test
    fun `the presenter can be started twice and stopped twice`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        presenter.start()
        requireProtection()
        runCurrent()
        assertTrue(presenter.state.value is AppLockOverlayState.Required)

        presenter.stop()
        presenter.stop()
        runCurrent()
        assertEquals(AppLockOverlayState.Idle, presenter.state.value)

        // A stopped presenter publishes nothing, whatever detection does afterwards.
        requireProtection()
        runCurrent()
        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
    }

    // ------------------------------------------------------------------ overlay capability

    @Test
    fun `a missing overlay grant is unpresentable, never idle`() = runTest {
        val presenter = presenter(
            scope = backgroundScope,
            overlay = FakeOverlayCapability(OverlayCapability.NotGranted),
        )

        presenter.start()
        requireProtection()
        runCurrent()

        val state = presenter.state.value
        assertTrue(state is AppLockOverlayState.Unpresentable)
        state as AppLockOverlayState.Unpresentable
        assertEquals(OverlayUnavailability.NotGranted, state.reason)
        assertEquals(protectedApplication, state.request.application)
    }

    @Test
    fun `an unreadable overlay capability is its own reason`() = runTest {
        val presenter = presenter(
            scope = backgroundScope,
            overlay = FakeOverlayCapability(OverlayCapability.Unavailable),
        )

        presenter.start()
        requireProtection()
        runCurrent()

        assertEquals(
            OverlayUnavailability.Unavailable,
            (presenter.state.value as AppLockOverlayState.Unpresentable).reason,
        )
    }

    @Test
    fun `a grant arriving later turns the requirement presentable`() = runTest {
        val overlay = FakeOverlayCapability(OverlayCapability.NotGranted)
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor, overlay = overlay)

        presenter.start()
        requireProtection(monitor)
        runCurrent()
        assertTrue(presenter.state.value is AppLockOverlayState.Unpresentable)

        overlay.capability = OverlayCapability.Granted
        reannounce(monitor)
        runCurrent()

        assertTrue(presenter.state.value is AppLockOverlayState.Required)
    }

    @Test
    fun `a refused window is reported, not swallowed`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        presenter.onSurfaceFailed(request.id)
        runCurrent()

        assertEquals(
            OverlayUnavailability.Failed,
            (presenter.state.value as AppLockOverlayState.Unpresentable).reason,
        )
    }

    @Test
    fun `a refused window for a superseded request changes nothing`() = runTest {
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor)

        presenter.start()
        requireProtection(monitor)
        runCurrent()
        val old = (presenter.state.value as AppLockOverlayState.Required).request

        observeForeground(monitor, ProtectedApplication("com.example.vault"))
        runCurrent()

        presenter.onSurfaceFailed(old.id)
        runCurrent()

        assertTrue("the current requirement must survive an old failure", presenter.state.value is AppLockOverlayState.Required)
    }

    // ------------------------------------------------------------------ primary credential

    @Test
    fun `a primary success reaches the session gate and removes the surface`() = runTest {
        val credentials = FakeCredentialManager()
        val presenter = presenter(scope = backgroundScope, credentials = credentials)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        credentials.nextOutcome = AuthenticationOutcome.Succeeded
        presenter.submitCredential(request.id, CredentialInput.Pin(charArrayOf('1', '2', '3', '4')))
        runCurrent()

        assertTrue("a successful credential must open the session", sessionManager.currentState().isAuthenticated)
        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
        assertEquals(1, credentials.verified)
    }

    @Test
    fun `a primary rejection opens nothing and keeps the surface`() = runTest {
        val credentials = FakeCredentialManager()
        val presenter = presenter(scope = backgroundScope, credentials = credentials)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        credentials.nextOutcome = AuthenticationOutcome.Failed(blockedForMillis = 5_000L)
        presenter.submitCredential(request.id, CredentialInput.Pin(charArrayOf('9', '9', '9', '9')))
        runCurrent()

        assertFalse(sessionManager.currentState().isAuthenticated)
        val state = presenter.state.value as AppLockOverlayState.Required
        assertEquals(AppLockPhase.AwaitingCredential, state.phase)
        assertEquals(
            ProtectionAttemptOutcome.Credential(AuthenticationOutcome.Failed(blockedForMillis = 5_000L)),
            state.lastAttempt,
        )
    }

    @Test
    fun `a temporarily blocked attempt keeps the surface and opens nothing`() = runTest {
        val credentials = FakeCredentialManager()
        val presenter = presenter(scope = backgroundScope, credentials = credentials)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        credentials.nextOutcome = AuthenticationOutcome.TemporarilyBlocked(retryAfterMillis = 30_000L)
        presenter.submitCredential(request.id, CredentialInput.Password(charArrayOf('x')))
        runCurrent()

        assertFalse(sessionManager.currentState().isAuthenticated)
        val state = presenter.state.value as AppLockOverlayState.Required
        assertEquals(AppLockPhase.AwaitingCredential, state.phase)
        assertNotNull(state.lastAttempt)
    }

    @Test
    fun `the failure counting stays inside the credential layer`() = runTest {
        val credentials = FakeCredentialManager()
        val biometrics = FakeBiometrics()
        val presenter = presenter(scope = backgroundScope, credentials = credentials, biometrics = biometrics)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        credentials.nextOutcome = AuthenticationOutcome.Failed(blockedForMillis = 0L)
        presenter.submitCredential(request.id, CredentialInput.Pin(charArrayOf('1', '2', '3', '4')))
        runCurrent()

        // App Lock owns no counters, no allow-list and no biometric configuration: the only calls it
        // makes are the ones that already exist for every other screen.
        assertEquals(0, biometrics.configurationCalls)
        assertEquals(0, credentials.enrolments)
        assertEquals(0, credentials.changes)
    }

    @Test
    fun `a submission that is not the current request is refused`() = runTest {
        val credentials = FakeCredentialManager()
        val presenter = presenter(scope = backgroundScope, credentials = credentials)

        presenter.start()
        requireProtection()
        runCurrent()
        val stale = (presenter.state.value as AppLockOverlayState.Required).request

        presenter.submitCredential(stale.id + 1, CredentialInput.Pin(charArrayOf('1', '2', '3', '4')))
        runCurrent()

        assertEquals("a stale submission must not even be verified", 0, credentials.verified)
        assertFalse(sessionManager.currentState().isAuthenticated)
    }

    @Test
    fun `a result that arrives after the request changed opens nothing`() = runTest {
        val credentials = FakeCredentialManager()
        val monitor = FakeMonitor()
        val presenter = presenter(scope = backgroundScope, monitor = monitor, credentials = credentials)
        val other = ProtectedApplication("com.example.vault")

        presenter.start()
        requireProtection(monitor)
        runCurrent()
        val first = (presenter.state.value as AppLockOverlayState.Required).request

        // The verification for A is in flight when the user switches to B: the attempt is held
        // inside the credential layer, exactly where a real derivation would be running.
        credentials.nextOutcome = AuthenticationOutcome.Succeeded
        credentials.gate = CompletableDeferred()
        presenter.submitCredential(first.id, CredentialInput.Pin(charArrayOf('1', '2', '3', '4')))
        runCurrent()

        observeForeground(monitor, other)
        runCurrent()

        // The result arrives now, for a request that is no longer the current one.
        credentials.gate?.complete(Unit)
        runCurrent()

        assertFalse(
            "an authentication for one application must not open the gate for another",
            sessionManager.currentState().isAuthenticated,
        )
        val state = presenter.state.value as AppLockOverlayState.Required
        assertEquals(other, state.request.application)
        assertEquals(AppLockPhase.AwaitingCredential, state.phase)
    }

    @Test
    fun `only one attempt runs at a time`() = runTest {
        val credentials = FakeCredentialManager()
        val presenter = presenter(scope = backgroundScope, credentials = credentials)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        credentials.nextOutcome = AuthenticationOutcome.Failed(blockedForMillis = 0L)
        credentials.gate = CompletableDeferred()
        presenter.submitCredential(request.id, CredentialInput.Pin(charArrayOf('1', '2', '3', '4')))
        runCurrent()

        // The first attempt is still running, so the second tap has nothing to start.
        presenter.submitCredential(request.id, CredentialInput.Pin(charArrayOf('5', '6', '7', '8')))
        runCurrent()
        credentials.gate?.complete(Unit)
        runCurrent()

        assertEquals("a second tap while an attempt runs must be refused", 1, credentials.verified)
    }

    // ------------------------------------------------------------------ biometrics

    @Test
    fun `a biometric success reaches the session gate and removes the surface`() = runTest {
        val biometrics = FakeBiometrics()
        val presenter = presenter(scope = backgroundScope, biometrics = biometrics)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        biometrics.nextOutcome = BiometricAuthenticationOutcome.Succeeded
        presenter.authenticateWithBiometric(request.id)
        runCurrent()

        assertTrue(sessionManager.currentState().isAuthenticated)
        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
    }

    @Test
    fun `a biometric rejection leaves the primary path in place`() = runTest {
        val biometrics = FakeBiometrics()
        val presenter = presenter(scope = backgroundScope, biometrics = biometrics)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        biometrics.nextOutcome = BiometricAuthenticationOutcome.Failed(attemptsRemaining = 2, blockedForMillis = 0L)
        presenter.authenticateWithBiometric(request.id)
        runCurrent()

        assertFalse(sessionManager.currentState().isAuthenticated)
        val state = presenter.state.value as AppLockOverlayState.Required
        assertEquals(AppLockPhase.AwaitingCredential, state.phase)
        assertEquals(PrimaryCredentialType.Pin, state.credential)
    }

    @Test
    fun `cancelling the platform prompt authenticates nothing`() = runTest {
        val biometrics = FakeBiometrics()
        val presenter = presenter(scope = backgroundScope, biometrics = biometrics)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        biometrics.nextOutcome = BiometricAuthenticationOutcome.Cancelled
        presenter.authenticateWithBiometric(request.id)
        runCurrent()

        assertFalse(sessionManager.currentState().isAuthenticated)
        assertTrue(presenter.state.value is AppLockOverlayState.Required)
        assertEquals(
            ProtectionAttemptOutcome.Biometric(BiometricAuthenticationOutcome.Cancelled),
            (presenter.state.value as AppLockOverlayState.Required).lastAttempt,
        )
    }

    @Test
    fun `a platform lockout is reported without weakening anything`() = runTest {
        val biometrics = FakeBiometrics()
        val presenter = presenter(scope = backgroundScope, biometrics = biometrics)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request

        biometrics.nextOutcome = BiometricAuthenticationOutcome.SystemBlocked(permanent = false)
        presenter.authenticateWithBiometric(request.id)
        runCurrent()

        assertFalse(sessionManager.currentState().isAuthenticated)
        assertTrue(presenter.state.value is AppLockOverlayState.Required)
    }

    @Test
    fun `biometrics that are not enabled are never offered`() = runTest {
        val presenter = presenter(
            scope = backgroundScope,
            biometrics = FakeBiometrics(status = BiometricStatus.Disabled),
        )

        presenter.start()
        requireProtection()
        runCurrent()

        assertEquals(
            BiometricStatus.Disabled,
            (presenter.state.value as AppLockOverlayState.Required).biometric,
        )
    }

    // ------------------------------------------------------------------ session behaviour

    @Test
    fun `an expired session requires authentication again`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        sessionManager.establish(AuthenticationOutcome.Succeeded)
        presenter.start()
        requireProtection()
        runCurrent()
        assertEquals(AppLockOverlayState.Idle, presenter.state.value)

        time.advanceBy(TEST_SESSION_TIMEOUT_MILLIS)
        // The next observation is what a running detector produces; the gate has already closed.
        requireProtection()
        runCurrent()

        assertTrue(presenter.state.value is AppLockOverlayState.Required)
    }

    @Test
    fun `Quick Lock requires authentication again`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request
        sessionManager.establish(AuthenticationOutcome.Succeeded)
        runCurrent()
        assertEquals(AppLockOverlayState.Idle, presenter.state.value)

        sessionManager.lockNow()
        // A requirement is raised again by detection, and it now needs the surface once more.
        presenter.start()
        requireProtection()
        runCurrent()

        assertTrue(presenter.state.value is AppLockOverlayState.Required)
        assertEquals(
            "the same application needs a new occasion after a lock",
            protectedApplication,
            (presenter.state.value as AppLockOverlayState.Required).request.application,
        )
        assertTrue(
            "a locked session needs a fresh occasion, not the one that was already satisfied",
            (presenter.state.value as AppLockOverlayState.Required).request.id != request.id,
        )
    }

    @Test
    fun `the session is read through the gate, never remembered`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        requireProtection()
        runCurrent()
        assertTrue(presenter.state.value is AppLockOverlayState.Required)

        sessionManager.establish(AuthenticationOutcome.Succeeded)
        runCurrent()

        assertEquals(AppLockOverlayState.Idle, presenter.state.value)
        assertEquals(SessionState.Authenticated::class.java, sessionManager.currentState()::class.java)
    }

    @Test
    fun `a session that ends while the surface is up needs a fresh authentication`() = runTest {
        val presenter = presenter(scope = backgroundScope)

        presenter.start()
        requireProtection()
        runCurrent()
        val request = (presenter.state.value as AppLockOverlayState.Required).request
        sessionManager.establish(AuthenticationOutcome.Succeeded)
        runCurrent()

        // The session runs out while the user is still inside the protected application.
        time.advanceBy(TEST_SESSION_TIMEOUT_MILLIS)
        presenter.start()
        requireProtection()
        runCurrent()

        val state = presenter.state.value as AppLockOverlayState.Required
        assertEquals(protectedApplication, state.request.application)
        assertTrue("a fresh occasion, not an extension of the old one", state.request.id != request.id)
    }

    @Test
    fun `no credential configured is reported as such, and nothing is guessed`() = runTest {
        val presenter = presenter(
            scope = backgroundScope,
            credentials = FakeCredentialManager(status = CredentialStatus.NotConfigured),
        )

        presenter.start()
        requireProtection()
        runCurrent()

        val state = presenter.state.value as AppLockOverlayState.Required
        assertNull(state.credential)
        assertTrue(presenter.state.value is AppLockOverlayState.Required)
    }

    // ------------------------------------------------------------------ helpers

    private fun presenter(
        scope: CoroutineScope,
        monitor: FakeMonitor = defaultMonitor,
        credentials: FakeCredentialManager = FakeCredentialManager(),
        biometrics: FakeBiometrics = FakeBiometrics(),
        overlay: FakeOverlayCapability = FakeOverlayCapability(OverlayCapability.Granted),
    ): AppLockOverlayPresenter = AppLockOverlayPresenter(
        monitor = monitor,
        sessionManager = sessionManager,
        credentialManager = credentials,
        biometrics = biometrics,
        overlayCapability = overlay,
        scope = scope,
    )

    /** Puts the protected application in front, the way a monitoring turn would report it. */
    private fun requireProtection(monitor: FakeMonitor = defaultMonitor) {
        observeForeground(monitor, ProtectedApplication(PROTECTED_PACKAGE))
    }

    /** Reports that the protected application is in front and needs authentication. */
    private fun observeForeground(monitor: FakeMonitor, application: ProtectedApplication) {
        val foreground = ForegroundApplication(application.packageName)
        monitor.state.value = AppLockState.Monitoring(
            foreground = foreground,
            decision = ProtectionDecision.AuthenticationRequired(application),
        )
        monitor.announce(application)
    }

    /** Announces the same requirement again, without changing the situation. */
    private fun reannounce(monitor: FakeMonitor) {
        val current = monitor.state.value
        val application = ((current as? AppLockState.Monitoring)?.decision as? ProtectionDecision.AuthenticationRequired)
            ?.application ?: return
        monitor.state.value = current
        monitor.announce(application)
    }

    private class FakeMonitor : AppLockMonitor {

        override val state = MutableStateFlow<AppLockState>(AppLockState.Stopped)

        private val mutableEvents = MutableSharedFlow<ProtectionEvent>(extraBufferCapacity = 8)

        override val events: SharedFlow<ProtectionEvent> = mutableEvents

        var starts = 0
        var stops = 0

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }

        /** Publishes a requirement the way the monitor would. */
        fun announce(application: ProtectedApplication) {
            mutableEvents.tryEmit(ProtectionEvent.AuthenticationRequired(application))
        }

        fun observe(foreground: ForegroundApplication) {
            state.value = AppLockState.Monitoring(foreground, ProtectionDecision.NoProtectionRequired)
        }

        fun becomeBlind(reason: DetectionUnavailability) {
            state.value = AppLockState.Unavailable(reason)
        }

        fun becomeStopped() {
            state.value = AppLockState.Stopped
        }
    }

    private class FakeCredentialManager(
        var status: CredentialStatus = CredentialStatus.Configured(PrimaryCredentialType.Pin),
    ) : CredentialManager {

        var nextOutcome: AuthenticationOutcome = AuthenticationOutcome.Failed(blockedForMillis = 0L)

        /** When set, verification waits for it — used to hold an attempt in flight. */
        var gate: CompletableDeferred<Unit>? = null

        var verified = 0
        var enrolments = 0
        var changes = 0

        override suspend fun status(): NivaraResult<CredentialStatus> = NivaraResult.Success(status)

        override suspend fun enroll(
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> {
            enrolments++
            return NivaraResult.Success(Unit)
        }

        override suspend fun verify(credential: CredentialInput): AuthenticationOutcome {
            verified++
            credential.clear()
            gate?.await()
            return nextOutcome
        }

        override suspend fun change(
            current: CredentialInput,
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> {
            changes++
            return NivaraResult.Failure(CredentialFailure.ProtectionFailed)
        }
    }

    private class FakeBiometrics(
        var status: BiometricStatus = BiometricStatus.Enabled,
    ) : BiometricAuthenticator {

        var nextOutcome: BiometricAuthenticationOutcome = BiometricAuthenticationOutcome.Cancelled

        /** Counts every call that would change the biometric configuration. */
        var configurationCalls = 0

        override fun attachHost(host: BiometricPromptHost) = Unit

        override fun detachHost(host: BiometricPromptHost) = Unit

        override suspend fun state(): BiometricState = BiometricState(status)

        override suspend fun enable(): NivaraResult<Unit> {
            configurationCalls++
            return NivaraResult.Success(Unit)
        }

        override suspend fun authenticate(): BiometricAuthenticationOutcome = nextOutcome

        override suspend fun disable(): NivaraResult<Unit> {
            configurationCalls++
            return NivaraResult.Failure(BiometricFailure.NotEnabled)
        }

        override suspend fun clearFailures() {
            configurationCalls++
        }
    }

    private class FakeOverlayCapability(
        var capability: OverlayCapability,
    ) : OverlayCapabilityRepository {

        override suspend fun status(): OverlayCapability = capability

        override suspend fun openSettings(): NivaraResult<Unit> = NivaraResult.Success(Unit)
    }

    private companion object {
        const val PROTECTED_PACKAGE = "com.example.camera"
    }
}