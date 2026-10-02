package com.nivara.app.ui.credential

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.session.InMemorySessionManager
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.AuthenticationSource
import com.nivara.app.domain.security.SessionState
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
import com.nivara.app.testing.testSessionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for how the verification screen meets the session gate.
 *
 * The credential manager is a stub of its interface — no derivation happens here, and no test
 * claims that one did. The session gate is the **real** manager, because the interesting
 * behaviour is what the screen does with what the gate says: it opens nothing on a rejection, and
 * it stops showing an open session the moment the gate closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialVerificationViewModelTest {

    private val time = MutableTimeProvider()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the screen starts by asking for the configured credential`() = runTest {
        val state = readyState(viewModel())

        assertEquals(CredentialVerificationStep.Enter, state.step)
        assertEquals(PrimaryCredentialType.Pin, state.type)
        assertFalse(state.session.isAuthenticated)
    }

    @Test
    fun `a verified credential opens a primary session`() = runTest {
        val manager = sessionManager()
        val viewModel = viewModel(sessionManager = manager)

        viewModel.submit(pin())

        val state = readyState(viewModel)
        assertEquals(CredentialVerificationStep.Succeeded, state.step)
        assertTrue(state.session is SessionState.Authenticated)
        assertEquals(AuthenticationSource.Primary, (state.session as SessionState.Authenticated).source)
        assertTrue(manager.isAuthenticated())
    }

    @Test
    fun `a rejected credential opens nothing`() = runTest {
        val manager = sessionManager()
        val viewModel = viewModel(
            credentials = FakeCredentialManager(verifyOutcome = AuthenticationOutcome.Failed(blockedForMillis = 0L)),
            sessionManager = manager,
        )

        viewModel.submit(pin())

        val state = readyState(viewModel)
        assertEquals(CredentialVerificationStep.Enter, state.step)
        assertFalse(state.session.isAuthenticated)
        assertFalse(manager.isAuthenticated())
        assertEquals(R.string.credential_error_failed, state.failure?.textRes)
    }

    @Test
    fun `a refusal while a delay is running opens nothing`() = runTest {
        val manager = sessionManager()
        val viewModel = viewModel(
            credentials = FakeCredentialManager(
                verifyOutcome = AuthenticationOutcome.TemporarilyBlocked(retryAfterMillis = 30_000L),
            ),
            sessionManager = manager,
        )

        viewModel.submit(pin())

        assertEquals(CredentialVerificationStep.Enter, readyState(viewModel).step)
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `a failed attempt does not end a session that is already open`() = runTest {
        val manager = sessionManager()
        // A session opened elsewhere — a biometric success, say — is not disturbed by a mistyped
        // credential on this screen.
        manager.establish(AuthenticationOutcome.Succeeded)
        val viewModel = viewModel(
            credentials = FakeCredentialManager(verifyOutcome = AuthenticationOutcome.Failed(blockedForMillis = 0L)),
            sessionManager = manager,
        )

        viewModel.submit(pin())

        assertTrue(manager.isAuthenticated())
        assertTrue(readyState(viewModel).session.isAuthenticated)
    }

    @Test
    fun `the return to the entry step keeps the configured method`() = runTest {
        val manager = sessionManager()
        val viewModel = viewModel(sessionManager = manager)
        viewModel.submit(pin())
        viewModel.lockNow()

        // Without the method, the entry field could not be drawn again and the next attempt would
        // be refused as an unusable configuration.
        assertEquals(PrimaryCredentialType.Pin, readyState(viewModel).type)
        assertEquals(CredentialVerificationStep.Enter, readyState(viewModel).step)

        viewModel.submit(pin())

        assertEquals(CredentialVerificationStep.Succeeded, readyState(viewModel).step)
        assertTrue(manager.isAuthenticated())
    }

    @Test
    fun `Quick Lock returns the screen to asking for the credential`() = runTest {
        val manager = sessionManager()
        val viewModel = viewModel(sessionManager = manager)
        viewModel.submit(pin())
        assertEquals(CredentialVerificationStep.Succeeded, readyState(viewModel).step)

        viewModel.lockNow()

        val state = readyState(viewModel)
        assertEquals(CredentialVerificationStep.Enter, state.step)
        assertFalse(state.session.isAuthenticated)
        assertEquals(R.string.session_notice_ended, state.notice?.textRes)
    }

    @Test
    fun `the timeout returns the screen to asking for the credential`() = runTest {
        val manager = sessionManager(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel(sessionManager = manager)
        viewModel.submit(pin())
        assertEquals(CredentialVerificationStep.Succeeded, readyState(viewModel).step)

        // Wall time passes, and the gate closes on its own.
        time.advanceBy(TEST_SESSION_TIMEOUT_MILLIS)
        advanceTimeBy(TEST_SESSION_TIMEOUT_MILLIS)
        runCurrent()

        val state = readyState(viewModel)
        assertEquals(CredentialVerificationStep.Enter, state.step)
        assertFalse(state.session.isAuthenticated)
        assertEquals(R.string.session_notice_ended, state.notice?.textRes)
    }

    @Test
    fun `the notice is cleared when the next attempt is submitted`() = runTest {
        val manager = sessionManager()
        val viewModel = viewModel(sessionManager = manager)
        viewModel.submit(pin())
        viewModel.lockNow()
        assertEquals(R.string.session_notice_ended, readyState(viewModel).notice?.textRes)

        viewModel.submit(pin())

        assertEquals(CredentialVerificationStep.Succeeded, readyState(viewModel).step)
        assertNull(readyState(viewModel).notice)
    }

    @Test
    fun `the screen can be opened and locked repeatedly`() = runTest {
        val manager = sessionManager()
        val viewModel = viewModel(sessionManager = manager)

        repeat(3) {
            viewModel.submit(pin())
            assertTrue(manager.isAuthenticated())
            viewModel.lockNow()
            assertFalse(manager.isAuthenticated())
        }

        assertEquals(CredentialVerificationStep.Enter, readyState(viewModel).step)
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * The real session manager.
     *
     * With no [timerDispatcher] its timer runs on a private virtual clock that nothing advances, so
     * a test that is not about the timeout cannot be surprised by it. The timeout test passes a
     * dispatcher built on the test's scheduler so it can move that clock deliberately.
     */
    private fun sessionManager(
        timerDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
    ): InMemorySessionManager = InMemorySessionManager(
        timeProvider = time,
        policy = testSessionPolicy(),
        scope = CoroutineScope(timerDispatcher + SupervisorJob()),
    )

    private fun viewModel(
        credentials: FakeCredentialManager = FakeCredentialManager(),
        sessionManager: InMemorySessionManager = sessionManager(),
    ): CredentialVerificationViewModel = CredentialVerificationViewModel(
        credentialManager = credentials,
        sessionManager = sessionManager,
    )

    private fun readyState(viewModel: CredentialVerificationViewModel): CredentialVerificationUiState =
        viewModel.uiState.value

    private fun pin(): CredentialInput.Pin = CredentialInput.Pin("2468".toCharArray())

    private class FakeCredentialManager(
        private var verifyOutcome: AuthenticationOutcome = AuthenticationOutcome.Succeeded,
    ) : CredentialManager {

        override suspend fun status(): NivaraResult<CredentialStatus> =
            NivaraResult.Success(CredentialStatus.Configured(PrimaryCredentialType.Pin))

        override suspend fun enroll(
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = notUsedByThisScreen()

        override suspend fun verify(credential: CredentialInput): AuthenticationOutcome {
            credential.clear()
            return verifyOutcome
        }

        override suspend fun change(
            current: CredentialInput,
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = notUsedByThisScreen()

        private fun notUsedByThisScreen(): Nothing =
            throw AssertionError("the verification screen must not use this credential operation")
    }
}
