package com.nivara.app.ui.biometric

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isSuccess
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricPromptHost
import com.nivara.app.domain.security.BiometricState
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.BiometricUnavailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Local JVM tests for the biometric screen's state machine.
 *
 * The authenticator and the credential manager are stubs of their *interfaces*: no test here
 * performs a biometric match, touches a key store or asks Android anything, and none of them
 * claims to. What is verified is the part that is pure logic — that a change to a security control
 * waits for the primary credential, that a rejected credential changes nothing, that a delay is
 * turned into a wait the screen can render, and that Android's lockout and Nivara's own delay stay
 * distinguishable in what the user is told.
 *
 * The real authenticator's decisions — key handling, invalidation, prompt error mapping, the
 * counter — are covered by the suites in `data/biometric`, and only a device can verify the prompt
 * itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the initial state reports what the authenticator says`() = runTest {
        val state = readyState(
            viewModel(FakeBiometricAuthenticator(currentState = BiometricState(status = BiometricStatus.Enabled))),
        )

        assertEquals(BiometricStatus.Enabled, state.status)
        assertNull(state.retryAtMillis)
        assertNull(state.failure)
        assertNull(state.pending)
        assertEquals(PrimaryCredentialType.Pin, state.credentialType)
    }

    @Test
    fun `with no credential the screen says so instead of offering to turn biometrics on`() = runTest {
        val authenticator = FakeBiometricAuthenticator(currentState = BiometricState(status = BiometricStatus.Disabled))
        val credentials = FakeCredentialManager(statusResult = NivaraResult.Success(CredentialStatus.NotConfigured))

        val state = readyState(viewModel(authenticator, credentials))

        assertNull(state.credentialType)
        assertEquals(0, authenticator.enableCalls)
    }

    @Test
    fun `Nivara's own delay becomes a wait the screen can show`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Enabled, retryAfterMillis = 30_000L),
        )

        val viewModel = viewModel(authenticator)
        val state = readyState(viewModel)

        assertEquals(NOW_MILLIS + 30_000L, state.retryAtMillis)
        assertEquals(30L, viewModel.secondsUntilRetry(state))
    }

    @Test
    fun `a successful attempt is reported as a success and not as an error`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Enabled),
            authenticateOutcome = BiometricAuthenticationOutcome.Succeeded,
        )
        val viewModel = viewModel(authenticator)

        viewModel.authenticate()

        assertEquals(1, authenticator.authenticateCalls)
        val state = readyState(viewModel)
        assertNull(state.failure)
        assertEquals(R.string.biometric_notice_authenticated, state.noticeRes)
    }

    @Test
    fun `a cancelled prompt is reported as a cancellation`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Enabled),
            authenticateOutcome = BiometricAuthenticationOutcome.Cancelled,
        )
        val viewModel = viewModel(authenticator)

        viewModel.authenticate()

        val state = readyState(viewModel)
        assertEquals(R.string.biometric_error_cancelled, state.failure?.textRes)
        assertNull(state.noticeRes)
    }

    @Test
    fun `a rejected biometric is reported as a rejection`() = runTest {
        val viewModel = viewModel(
            FakeBiometricAuthenticator(
                currentState = BiometricState(status = BiometricStatus.Enabled),
                authenticateOutcome = BiometricAuthenticationOutcome.Failed(
                    attemptsRemaining = 4,
                    blockedForMillis = 0L,
                ),
            ),
        )

        viewModel.authenticate()

        assertEquals(R.string.biometric_error_failed, readyState(viewModel).failure?.textRes)
    }

    @Test
    fun `Android's lockout is reported as Android's lockout, never as Nivara's delay`() = runTest {
        val viewModel = viewModel(
            FakeBiometricAuthenticator(
                currentState = BiometricState(status = BiometricStatus.Enabled),
                authenticateOutcome = BiometricAuthenticationOutcome.SystemBlocked(permanent = false),
            ),
        )

        viewModel.authenticate()

        val state = readyState(viewModel)
        assertEquals(R.string.biometric_error_system_locked, state.failure?.textRes)
        // Nivara shows no deadline of its own for the platform's lockout, because it cannot know it.
        assertNull(state.retryAtMillis)
    }

    @Test
    fun `an unusable configuration is reported as the platform's reason`() = runTest {
        val viewModel = viewModel(
            FakeBiometricAuthenticator(
                currentState = BiometricState(status = BiometricStatus.Enabled),
                authenticateOutcome = BiometricAuthenticationOutcome.Unavailable(
                    BiometricUnavailability.HardwareUnavailable,
                ),
            ),
        )

        viewModel.authenticate()

        assertEquals(
            R.string.biometric_status_unavailable_hardware,
            readyState(viewModel).failure?.textRes,
        )
    }

    @Test
    fun `turning biometric unlock on verifies the primary credential first`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Disabled),
        )
        val credentials = FakeCredentialManager(verifyOutcome = AuthenticationOutcome.Succeeded)
        val viewModel = viewModel(authenticator, credentials)

        viewModel.requestChange(BiometricPendingChange.TurnOn)
        assertEquals(BiometricPendingChange.TurnOn, readyState(viewModel).pending)

        viewModel.submitPrimary(pin())

        assertEquals(1, credentials.verifyCalls)
        assertEquals(1, authenticator.enableCalls)
        val state = readyState(viewModel)
        assertNull(state.pending)
        assertEquals(R.string.biometric_notice_enabled, state.noticeRes)
    }

    @Test
    fun `a rejected primary credential changes nothing`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Disabled),
        )
        val credentials = FakeCredentialManager(
            verifyOutcome = AuthenticationOutcome.Failed(blockedForMillis = 0L),
        )
        val viewModel = viewModel(authenticator, credentials)

        viewModel.requestChange(BiometricPendingChange.TurnOn)
        viewModel.submitPrimary(pin())

        assertEquals(1, credentials.verifyCalls)
        assertEquals(0, authenticator.enableCalls)
        val state = readyState(viewModel)
        assertNotNull("the rejection must be shown", state.failure)
        // The pending change stays on screen, so the user can retry or back out.
        assertEquals(BiometricPendingChange.TurnOn, state.pending)
    }

    @Test
    fun `a change the platform refuses is reported and clears the pending change`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Disabled),
            enableResult = NivaraResult.Failure(BiometricFailure.PrimaryCredentialRequired),
        )
        val viewModel = viewModel(authenticator)

        viewModel.requestChange(BiometricPendingChange.TurnOn)
        viewModel.submitPrimary(pin())

        val state = readyState(viewModel)
        assertEquals(R.string.biometric_error_primary_required, state.failure?.textRes)
        assertNull(state.pending)
    }

    @Test
    fun `turning biometric unlock off is authenticated too`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Enabled),
        )
        val viewModel = viewModel(authenticator)

        viewModel.requestChange(BiometricPendingChange.TurnOff)
        viewModel.submitPrimary(pin())

        assertEquals(1, authenticator.disableCalls)
        assertEquals(R.string.biometric_notice_disabled, readyState(viewModel).noticeRes)
    }

    @Test
    fun `proving the primary credential is what forgets Nivara's biometric failures`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Enabled, retryAfterMillis = 30_000L),
        )
        val viewModel = viewModel(authenticator)

        viewModel.requestChange(BiometricPendingChange.ClearDelay)
        viewModel.submitPrimary(pin())

        assertEquals(1, authenticator.clearFailuresCalls)
        val state = readyState(viewModel)
        assertEquals(R.string.biometric_notice_delay_cleared, state.noticeRes)
        assertNull("the pause should be gone from the screen", state.retryAtMillis)
    }

    @Test
    fun `backing out of a pending change touches nothing`() = runTest {
        val authenticator = FakeBiometricAuthenticator(
            currentState = BiometricState(status = BiometricStatus.Disabled),
        )
        val credentials = FakeCredentialManager()
        val viewModel = viewModel(authenticator, credentials)

        viewModel.requestChange(BiometricPendingChange.TurnOn)
        viewModel.cancelChange()

        assertNull(readyState(viewModel).pending)
        assertEquals(0, credentials.verifyCalls)
        assertEquals(0, authenticator.enableCalls)
    }

    // ------------------------------------------------------------------ fixtures

    private fun viewModel(
        authenticator: FakeBiometricAuthenticator,
        credentials: FakeCredentialManager = FakeCredentialManager(),
    ): BiometricViewModel = BiometricViewModel(
        authenticator = authenticator,
        credentialManager = credentials,
        clockMillis = { NOW_MILLIS },
        backgroundDispatcher = UnconfinedTestDispatcher(),
    )

    private fun readyState(viewModel: BiometricViewModel): BiometricUiState.Ready {
        val state = viewModel.uiState.value
        require(state is BiometricUiState.Ready) { "the screen should be ready but was $state" }
        return state
    }

    private fun pin(): CredentialInput.Pin = CredentialInput.Pin("2468".toCharArray())

    private class FakeBiometricAuthenticator(
        private var currentState: BiometricState = BiometricState(status = BiometricStatus.Disabled),
        private var authenticateOutcome: BiometricAuthenticationOutcome =
            BiometricAuthenticationOutcome.Cancelled,
        private var enableResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
        private var disableResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
    ) : BiometricAuthenticator {

        var enableCalls: Int = 0
            private set
        var disableCalls: Int = 0
            private set
        var authenticateCalls: Int = 0
            private set
        var clearFailuresCalls: Int = 0
            private set

        override fun attachHost(host: BiometricPromptHost) = Unit

        override fun detachHost(host: BiometricPromptHost) = Unit

        override suspend fun state(): BiometricState = currentState

        override suspend fun enable(): NivaraResult<Unit> {
            enableCalls++
            if (enableResult.isSuccess) {
                currentState = BiometricState(status = BiometricStatus.Enabled)
            }
            return enableResult
        }

        override suspend fun authenticate(): BiometricAuthenticationOutcome {
            authenticateCalls++
            return authenticateOutcome
        }

        override suspend fun disable(): NivaraResult<Unit> {
            disableCalls++
            if (disableResult.isSuccess) {
                currentState = BiometricState(status = BiometricStatus.Disabled)
            }
            return disableResult
        }

        override suspend fun clearFailures() {
            clearFailuresCalls++
            currentState = currentState.copy(retryAfterMillis = 0L)
        }
    }

    private class FakeCredentialManager(
        private var statusResult: NivaraResult<CredentialStatus> =
            NivaraResult.Success(CredentialStatus.Configured(PrimaryCredentialType.Pin)),
        private var verifyOutcome: AuthenticationOutcome = AuthenticationOutcome.Succeeded,
    ) : CredentialManager {

        var verifyCalls: Int = 0
            private set

        override suspend fun status(): NivaraResult<CredentialStatus> = statusResult

        override suspend fun enroll(
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = notUsedByThisScreen()

        override suspend fun verify(credential: CredentialInput): AuthenticationOutcome {
            verifyCalls++
            // The real manager takes ownership of the buffer and clears it; so does this stub, so a
            // test cannot accidentally depend on a credential outliving the call.
            credential.clear()
            return verifyOutcome
        }

        override suspend fun change(
            current: CredentialInput,
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = notUsedByThisScreen()

        private fun notUsedByThisScreen(): Nothing =
            throw AssertionError("the biometric screen must not use this credential operation")
    }

    private companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
    }
}
