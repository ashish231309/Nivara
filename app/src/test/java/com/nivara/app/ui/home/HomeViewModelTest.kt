package com.nivara.app.ui.home

import com.nivara.app.core.common.NivaraResult
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
 * There is no Android dependency here: the view model talks to the [DeviceSecurityProvider]
 * contract, which is why the screen can be verified without a device.
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
    fun `reports ready when a screen lock is configured`() {
        val viewModel = HomeViewModel(FakeDeviceSecurityProvider(NivaraResult.Success(true)))

        assertEquals(HomeUiState.Ready(deviceLockConfigured = true), viewModel.uiState.value)
    }

    @Test
    fun `reports ready when no screen lock is configured`() {
        val viewModel = HomeViewModel(FakeDeviceSecurityProvider(NivaraResult.Success(false)))

        assertEquals(HomeUiState.Ready(deviceLockConfigured = false), viewModel.uiState.value)
    }

    @Test
    fun `reports an error when the provider fails`() {
        val viewModel = HomeViewModel(
            FakeDeviceSecurityProvider(NivaraResult.Failure(IllegalStateException("unavailable"))),
        )

        assertEquals(HomeUiState.Error, viewModel.uiState.value)
    }

    @Test
    fun `refresh recovers from the error state`() {
        val provider = FakeDeviceSecurityProvider(NivaraResult.Failure(IllegalStateException("unavailable")))
        val viewModel = HomeViewModel(provider)

        assertEquals(HomeUiState.Error, viewModel.uiState.value)

        provider.nextResult = NivaraResult.Success(true)
        viewModel.refresh()

        assertEquals(HomeUiState.Ready(deviceLockConfigured = true), viewModel.uiState.value)
        assertEquals(2, provider.callCount)
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
}
