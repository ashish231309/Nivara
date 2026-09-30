package com.nivara.app.ui.home

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricPromptHost
import com.nivara.app.domain.security.BiometricState
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.BiometricUnavailability
import com.nivara.app.domain.security.DeviceSecurityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Local JVM tests for the home screen state machine.
 *
 * There is no Android dependency here: the view model talks to the domain contracts
 * [DeviceSecurityProvider], [CredentialManager] and [BiometricAuthenticator], which is why the
 * screen can be verified without a device.
 *
 * The managers are small stubs rather than the real implementations on purpose. This is a test of
 * how the screen maps three results into a state; the real credential cryptography is covered by
 * `NivaraCredentialManagerTest`, and nothing here stands in for a key derivation or for a
 * biometric match.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which only exists on Android.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `reports ready with no credential when a screen lock is configured`() {
        val viewModel = HomeViewModel(
            deviceSecurityProvider = FakeDeviceSecurityProvider(NivaraResult.Success(true)),
            credentialManager = FakeCredentialManager(NivaraResult.Success(CredentialStatus.NotConfigured)),
            biometricAuthenticator = FakeBiometricAuthenticator(BiometricStatus.Disabled),
        )

        assertEquals(
            HomeUiState.Ready(
                deviceLockConfigured = true,
                credentialType = null,
                biometricStatus = BiometricStatus.Disabled,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `reports ready with the active credential type`() {
        val viewModel = HomeViewModel(
            deviceSecurityProvider = FakeDeviceSecurityProvider(NivaraResult.Success(false)),
            credentialManager = FakeCredentialManager(
                NivaraResult.Success(CredentialStatus.Configured(PrimaryCredentialType.Pin)),
            ),
            biometricAuthenticator = FakeBiometricAuthenticator(BiometricStatus.Enabled),
        )

        assertEquals(
            HomeUiState.Ready(
                deviceLockConfigured = false,
                credentialType = PrimaryCredentialType.Pin,
                biometricStatus = BiometricStatus.Enabled,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `reports the device's own reason when biometrics are unavailable`() {
        val viewModel = HomeViewModel(
            deviceSecurityProvider = FakeDeviceSecurityProvider(NivaraResult.Success(true)),
            credentialManager = FakeCredentialManager(NivaraResult.Success(CredentialStatus.NotConfigured)),
            biometricAuthenticator = FakeBiometricAuthenticator(
                BiometricStatus.Unavailable(BiometricUnavailability.NotEnrolled),
            ),
        )

        assertEquals(
            HomeUiState.Ready(
                deviceLockConfigured = true,
                credentialType = null,
                biometricStatus = BiometricStatus.Unavailable(BiometricUnavailability.NotEnrolled),
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `reports an error when the device security provider fails`() {
        val viewModel = HomeViewModel(
            deviceSecurityProvider = FakeDeviceSecurityProvider(
                NivaraResult.Failure(IllegalStateException("unavailable")),
            ),
            credentialManager = FakeCredentialManager(NivaraResult.Success(CredentialStatus.NotConfigured)),
            biometricAuthenticator = FakeBiometricAuthenticator(BiometricStatus.Disabled),
        )

        assertEquals(HomeUiState.Error, viewModel.uiState.value)
    }

    @Test
    fun `reports an error when the credential record cannot be read`() {
        val viewModel = HomeViewModel(
            deviceSecurityProvider = FakeDeviceSecurityProvider(NivaraResult.Success(true)),
            credentialManager = FakeCredentialManager(
                NivaraResult.Failure(IllegalStateException("unreadable")),
            ),
            biometricAuthenticator = FakeBiometricAuthenticator(BiometricStatus.Disabled),
        )

        assertEquals(HomeUiState.Error, viewModel.uiState.value)
    }

    @Test
    fun `refresh recovers from the error state`() {
        val provider = FakeDeviceSecurityProvider(NivaraResult.Failure(IllegalStateException("unavailable")))
        val credentials = FakeCredentialManager(NivaraResult.Success(CredentialStatus.NotConfigured))
        val biometrics = FakeBiometricAuthenticator(BiometricStatus.Disabled)
        val viewModel = HomeViewModel(provider, credentials, biometrics)

        assertEquals(HomeUiState.Error, viewModel.uiState.value)
        // The failed first load stops at the device check, so neither of the other two is asked.
        assertEquals(0, credentials.callCount)
        assertEquals(0, biometrics.callCount)

        provider.nextResult = NivaraResult.Success(true)
        viewModel.refresh()

        assertEquals(
            HomeUiState.Ready(
                deviceLockConfigured = true,
                credentialType = null,
                biometricStatus = BiometricStatus.Disabled,
            ),
            viewModel.uiState.value,
        )
        assertEquals(2, provider.callCount)
        assertEquals(1, credentials.callCount)
        assertEquals(1, biometrics.callCount)
    }

    private class FakeDeviceSecurityProvider(
        var nextResult: NivaraResult<Boolean>,
    ) : DeviceSecurityProvider {

        var callCount: Int = 0
            private set

        override suspend fun isDeviceLockConfigured(): NivaraResult<Boolean> {
            callCount++
            return nextResult
        }
    }

    /**
     * Reports a canned status. The home screen only reads it, so the mutating operations are not
     * part of this test and say so loudly if they are ever called through it.
     */
    private class FakeCredentialManager(
        var nextStatus: NivaraResult<CredentialStatus>,
    ) : CredentialManager {

        var callCount: Int = 0
            private set

        override suspend fun status(): NivaraResult<CredentialStatus> {
            callCount++
            return nextStatus
        }

        override suspend fun enroll(
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = notUsedByTheHomeScreen()

        override suspend fun verify(credential: CredentialInput): AuthenticationOutcome =
            notUsedByTheHomeScreen()

        override suspend fun change(
            current: CredentialInput,
            credential: CredentialInput,
            confirmation: CredentialInput,
        ): NivaraResult<Unit> = notUsedByTheHomeScreen()

        private fun notUsedByTheHomeScreen(): Nothing =
            throw AssertionError("the home screen must not use this operation")
    }

    /**
     * Reports a canned biometric status.
     *
     * This stub stands in for the *interface*, never for Android: no test here performs a biometric
     * match, and no test here claims that one happened. The real authenticator's JVM-testable
     * decisions are covered by their own suites.
     */
    private class FakeBiometricAuthenticator(
        private val status: BiometricStatus,
    ) : BiometricAuthenticator {

        var callCount: Int = 0
            private set

        override fun attachHost(host: BiometricPromptHost) = notUsedByTheHomeScreen()

        override fun detachHost(host: BiometricPromptHost) = notUsedByTheHomeScreen()

        override suspend fun state(): BiometricState {
            callCount++
            return BiometricState(status = status)
        }

        override suspend fun enable(): NivaraResult<Unit> = notUsedByTheHomeScreen()

        override suspend fun authenticate(): BiometricAuthenticationOutcome = notUsedByTheHomeScreen()

        override suspend fun disable(): NivaraResult<Unit> = notUsedByTheHomeScreen()

        override suspend fun clearFailures() = notUsedByTheHomeScreen()

        private fun notUsedByTheHomeScreen(): Nothing =
            throw AssertionError("the home screen must not use this operation")
    }
}
