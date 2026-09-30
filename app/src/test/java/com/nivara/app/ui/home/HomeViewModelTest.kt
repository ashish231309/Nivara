package com.nivara.app.ui.home

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
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
 * [DeviceSecurityProvider] and [CredentialManager], which is why the screen can be verified
 * without a device.
 *
 * The credential manager is a small stub rather than the real implementation on purpose. This is
 * a test of how the screen maps two results into a state, and the real manager's cryptography is
 * covered by `NivaraCredentialManagerTest`. Nothing here stands in for a key derivation.
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
        )

        assertEquals(
            HomeUiState.Ready(deviceLockConfigured = true, credentialType = null),
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
        )

        assertEquals(
            HomeUiState.Ready(
                deviceLockConfigured = false,
                credentialType = PrimaryCredentialType.Pin,
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
        )

        assertEquals(HomeUiState.Error, viewModel.uiState.value)
    }

    @Test
    fun `refresh recovers from the error state`() {
        val provider = FakeDeviceSecurityProvider(NivaraResult.Failure(IllegalStateException("unavailable")))
        val credentials = FakeCredentialManager(NivaraResult.Success(CredentialStatus.NotConfigured))
        val viewModel = HomeViewModel(provider, credentials)

        assertEquals(HomeUiState.Error, viewModel.uiState.value)

        provider.nextResult = NivaraResult.Success(true)
        viewModel.refresh()

        assertEquals(
            HomeUiState.Ready(deviceLockConfigured = true, credentialType = null),
            viewModel.uiState.value,
        )
        assertEquals(2, provider.callCount)
        assertEquals(2, credentials.callCount)
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
}
