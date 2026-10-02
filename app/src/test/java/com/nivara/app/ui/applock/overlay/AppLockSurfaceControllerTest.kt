package com.nivara.app.ui.applock.overlay

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockOverlayPresenter
import com.nivara.app.domain.applock.AppLockOverlayState
import com.nivara.app.domain.applock.AppLockState
import com.nivara.app.domain.applock.ForegroundApplication
import com.nivara.app.domain.applock.ProtectedApplication
import com.nivara.app.domain.applock.ProtectionDecision
import com.nivara.app.domain.applock.ProtectionEvent
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricPromptHost
import com.nivara.app.domain.security.BiometricState
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.testSessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the protection window's lifecycle.
 *
 * The window itself is the one thing a JVM cannot test; what it *can* test is everything the window
 * is told to do, which is where the rules this stage cares about live: one window however often a
 * requirement is announced, a window that follows the current request instead of being rebuilt, a
 * removal on every ending, and cleanup that can be called any number of times without complaining.
 * The platform calls beneath the seam are asserted — as far as they can be — by the instrumented
 * suite, and are declared as compiled-only here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockSurfaceControllerTest {

    private val time = MutableTimeProvider()
    private val sessionManager: SessionManager = testSessionManager(timeProvider = time)

    @Test
    fun `nothing is attached before anything is required`() = runTest {
        val wired = wired()

        wired.controller.start()
        runCurrent()

        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
        assertEquals(0, wired.surface.attachCalls)
    }

    @Test
    fun `a requirement attaches exactly one window`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()

        assertEquals(1, wired.surface.attachCalls)
        assertTrue(wired.controller.state.value is AppLockSurfaceState.Showing)
    }

    @Test
    fun `a repeated requirement does not attach a second window`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()
        repeat(4) {
            wired.monitor.announceAgain()
            runCurrent()
        }

        assertEquals("one occasion is one window", 1, wired.surface.attachCalls)
        assertEquals(0, wired.surface.detachCalls)
    }

    @Test
    fun `a second protected application reuses the window and removes it once on the way out`() = runTest {
        val wired = wired()
        val other = ProtectedApplication("com.example.vault")

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()

        wired.monitor.observeForeground(other)
        runCurrent()

        assertEquals("the window follows the occasion rather than being rebuilt", 1, wired.surface.attachCalls)
        assertEquals(other, (wired.controller.state.value as AppLockSurfaceState.Showing).request.application)

        wired.monitor.observeOther()
        runCurrent()

        assertEquals(1, wired.surface.detachCalls)
        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
    }

    @Test
    fun `the lifecycle runs idle, showing, dismissing, idle`() = runTest {
        val wired = wired()
        // What the controller's state says *while* the window is being taken down. Reading it from
        // the removal itself is what makes the transient Dismissing phase observable: a collector
        // would see only the state that follows it.
        val duringRemoval = mutableListOf<AppLockSurfaceState>()
        wired.surface.onDetach { duringRemoval += wired.controller.state.value }

        wired.controller.start()
        runCurrent()
        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)

        wired.monitor.requireProtection()
        runCurrent()
        assertTrue(wired.controller.state.value is AppLockSurfaceState.Showing)

        wired.monitor.observeOther()
        runCurrent()

        assertEquals("the window is removed exactly once", 1, duringRemoval.size)
        assertTrue(
            "and the state says Dismissing while it is",
            duringRemoval.single() is AppLockSurfaceState.Dismissing,
        )
        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
    }

    @Test
    fun `an authentication that opens the session removes the window`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()
        val request = (wired.controller.state.value as AppLockSurfaceState.Showing).request

        wired.presenter.submitCredential(request.id, CredentialInput.Pin(charArrayOf('1', '2', '3', '4')))
        runCurrent()

        assertTrue(sessionManager.currentState().isAuthenticated)
        assertEquals(1, wired.surface.detachCalls)
        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
    }

    @Test
    fun `stopping removes the window and is idempotent`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()
        assertEquals(1, wired.surface.attachCalls)

        wired.controller.stop()
        wired.controller.stop()
        runCurrent()

        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
        assertFalse(wired.surface.attached)
        assertEquals("a second stop has nothing to remove", 1, wired.surface.detachCalls)
    }

    @Test
    fun `stopping without starting is harmless`() = runTest {
        val wired = wired()

        wired.controller.stop()
        runCurrent()

        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
        assertEquals(0, wired.surface.attachCalls)
        assertEquals(0, wired.surface.detachCalls)
    }

    @Test
    fun `starting twice keeps one window`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()

        assertEquals(1, wired.surface.attachCalls)
    }

    @Test
    fun `a refused window is reported and leaves no window state behind`() = runTest {
        val wired = wired(attachSucceeds = false)

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()

        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
        assertTrue(wired.presenter.state.value is AppLockOverlayState.Unpresentable)
        assertEquals(1, wired.surface.attachCalls)
    }

    @Test
    fun `a window the platform removed is not silently forgotten`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()
        assertTrue(wired.controller.state.value is AppLockSurfaceState.Showing)

        // The overlay grant was revoked, so Android took the window away.
        wired.surface.simulateUnexpectedDetach()
        runCurrent()

        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
        assertTrue(
            "the requirement must be reported as one that could not be presented",
            wired.presenter.state.value is AppLockOverlayState.Unpresentable,
        )
        assertEquals("no re-attachment loop; the next occasion tries again", 1, wired.surface.attachCalls)
    }

    @Test
    fun `a removal the controller asked for is not reported as a failure`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.monitor.requireProtection()
        runCurrent()

        wired.monitor.observeOther()
        runCurrent()

        assertFalse(wired.presenter.state.value is AppLockOverlayState.Unpresentable)
        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
    }

    @Test
    fun `a stopped controller publishes nothing when detection speaks again`() = runTest {
        val wired = wired()

        wired.controller.start()
        wired.controller.stop()
        runCurrent()

        wired.monitor.requireProtection()
        runCurrent()

        assertEquals(AppLockSurfaceState.Idle, wired.controller.state.value)
        assertEquals(0, wired.surface.attachCalls)
    }

    // ------------------------------------------------------------------ helpers

    private fun TestScope.wired(
        attachSucceeds: Boolean = true,
        overlay: OverlayCapability = OverlayCapability.Granted,
    ): Wired {
        val surface = FakeSurface(attachSucceeds = attachSucceeds)
        val monitor = FakeMonitor()
        val presenter = AppLockOverlayPresenter(
            monitor = monitor,
            sessionManager = sessionManager,
            credentialManager = FakeCredentialManager(),
            biometrics = FakeBiometrics(),
            overlayCapability = FakeOverlayCapability(overlay),
            scope = backgroundScope,
        )
        return Wired(
            controller = AppLockSurfaceController(
                surface = surface,
                presenter = presenter,
                scope = backgroundScope,
            ),
            presenter = presenter,
            monitor = monitor,
            surface = surface,
        )
    }

    private class Wired(
        val controller: AppLockSurfaceController,
        val presenter: AppLockOverlayPresenter,
        val monitor: FakeMonitor,
        val surface: FakeSurface,
    )

    private class FakeSurface(
        private val attachSucceeds: Boolean = true,
    ) : OverlaySurface {

        var attachCalls = 0
        var detachCalls = 0
        var attached = false

        private var onUnexpectedDetach: (() -> Unit)? = null

        /** Invoked while a requested removal is in progress, before the window is reported gone. */
        private var onDetachAction: (() -> Unit)? = null

        /** Registers a callback that runs during a removal the controller asked for. */
        fun onDetach(action: () -> Unit) {
            onDetachAction = action
        }

        override fun attach(onUnexpectedDetach: () -> Unit): Boolean {
            attachCalls++
            if (!attachSucceeds) return false
            this.onUnexpectedDetach = onUnexpectedDetach
            attached = true
            return true
        }

        override fun detach() {
            if (!attached) return
            detachCalls++
            onDetachAction?.invoke()
            attached = false
            onUnexpectedDetach = null
        }

        /** The platform taking the window away without a request. */
        fun simulateUnexpectedDetach() {
            if (!attached) return
            attached = false
            val callback = onUnexpectedDetach
            onUnexpectedDetach = null
            callback?.invoke()
        }
    }

    private class FakeMonitor : AppLockMonitor {

        override val state = MutableStateFlow<AppLockState>(AppLockState.Stopped)

        private val mutableEvents = MutableSharedFlow<ProtectionEvent>(extraBufferCapacity = 8)

        override val events: SharedFlow<ProtectionEvent> = mutableEvents

        override fun start() = Unit

        override fun stop() = Unit

        fun requireProtection() = observeForeground(ProtectedApplication(PROTECTED_PACKAGE))

        fun announceAgain() {
            val current = state.value
            val application = ((current as? AppLockState.Monitoring)?.decision as? ProtectionDecision.AuthenticationRequired)
                ?.application ?: return
            state.value = current
            mutableEvents.tryEmit(ProtectionEvent.AuthenticationRequired(application))
        }

        fun observeForeground(application: ProtectedApplication) {
            state.value = AppLockState.Monitoring(
                foreground = ForegroundApplication(application.packageName),
                decision = ProtectionDecision.AuthenticationRequired(application),
            )
            mutableEvents.tryEmit(ProtectionEvent.AuthenticationRequired(application))
        }

        fun observeOther() {
            state.value = AppLockState.Monitoring(
                foreground = ForegroundApplication("com.example.notes"),
                decision = ProtectionDecision.NoProtectionRequired,
            )
        }
    }

    private class FakeCredentialManager : CredentialManager {

        override suspend fun status(): NivaraResult<CredentialStatus> =
            NivaraResult.Success(CredentialStatus.Configured(PrimaryCredentialType.Pin))

        override suspend fun enroll(
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = NivaraResult.Success(Unit)

        override suspend fun verify(credential: CredentialInput): AuthenticationOutcome {
            credential.clear()
            return AuthenticationOutcome.Succeeded
        }

        override suspend fun change(
            current: CredentialInput,
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = NivaraResult.Success(Unit)
    }

    private class FakeBiometrics : BiometricAuthenticator {

        override fun attachHost(host: BiometricPromptHost) = Unit

        override fun detachHost(host: BiometricPromptHost) = Unit

        override suspend fun state(): BiometricState = BiometricState(BiometricStatus.Enabled)

        override suspend fun enable(): NivaraResult<Unit> = NivaraResult.Success(Unit)

        override suspend fun authenticate(): BiometricAuthenticationOutcome =
            BiometricAuthenticationOutcome.Cancelled

        override suspend fun disable(): NivaraResult<Unit> = NivaraResult.Success(Unit)

        override suspend fun clearFailures() = Unit
    }

    private class FakeOverlayCapability(private val capability: OverlayCapability) : OverlayCapabilityRepository {

        override suspend fun status(): OverlayCapability = capability

        override suspend fun openSettings(): NivaraResult<Unit> = NivaraResult.Success(Unit)
    }

    private companion object {
        const val PROTECTED_PACKAGE = "com.example.camera"
    }
}
