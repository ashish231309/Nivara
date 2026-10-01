package com.nivara.app.ui.applock

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.app.ApplicationDiscoveryState
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local JVM tests for the App Lock preparation screen's state machine.
 *
 * The repositories are stubs of their *interfaces*: no test here queries a device, reads a
 * permission or opens a settings screen, and none of them claims to. What is verified is the logic
 * the screen depends on — that both capabilities become one state, that a failed discovery is not
 * drawn as an empty device, that opening Android's settings is never treated as a grant, that a
 * return from those settings is noticed, and that nothing throws when a repository misbehaves.
 *
 * Whether the platform actually reports a grant, returns the launcher list or opens the settings
 * screen is only knowable on a device, and the instrumented suite that checks it is compiled but
 * not executed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockSetupViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which only exists on Android.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `both capabilities are loaded into the ready state`() = runTest {
        val model = viewModel()

        val state = readyState(model)

        assertEquals(ApplicationDiscoveryState.Available(applications), state.setup.discovery)
        assertEquals(UsageAccessStatus.NotGranted, state.setup.usageAccess)
        assertFalse(state.busy)
        assertNull(state.failure)
        assertNull(state.noticeRes)
    }

    @Test
    fun `everything in place reads as ready`() = runTest {
        val model = viewModel(usage = FakeUsageAccessRepository(UsageAccessStatus.Granted))

        val state = readyState(model)

        assertTrue(state.setup.isReady)
        assertEquals(UsageAccessStatus.Granted, state.setup.usageAccess)
    }

    @Test
    fun `a failed discovery is reported as unavailable, not as an empty device`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Failure()))

        val state = readyState(model)

        assertEquals(ApplicationDiscoveryState.Unavailable, state.setup.discovery)
        assertTrue(state.setup.missingPrerequisites.contains(AppLockPrerequisite.ApplicationDiscovery))
    }

    @Test
    fun `a repository that throws becomes the retryable error state instead of a crash`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(applications)).apply {
            thrown = IllegalStateException("the platform failed")
        }

        val model = viewModel(repository = repository)

        assertEquals(AppLockSetupUiState.Error, model.uiState.value)
    }

    @Test
    fun `resuming re-reads both capabilities`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(applications))
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted)
        val model = viewModel(repository = repository, usage = usage)
        assertEquals(1, repository.calls)

        model.onResumed()

        assertEquals(2, repository.calls)
        assertEquals(2, usage.statusCalls)
    }

    @Test
    fun `a resume picks up an application that appeared while the screen was away`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(applications))
        val model = viewModel(repository = repository)
        assertEquals(2, readyState(model).setup.applications.size)

        repository.result = NivaraResult.Success(
            applications + InstalledApplication("com.example.maps", "Maps"),
        )
        model.onResumed()

        assertEquals(3, readyState(model).setup.applications.size)
    }

    @Test
    fun `opening Android's settings is never treated as a grant`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted)
        val model = viewModel(usage = usage)

        model.openUsageAccessSettings()

        assertEquals(1, usage.openSettingsCalls)
        val state = readyState(model)
        assertEquals(UsageAccessStatus.NotGranted, state.setup.usageAccess)
        assertFalse(state.setup.isReady)
        assertFalse(state.busy)
        assertNull(state.failure)
        assertNull(state.noticeRes)
    }

    @Test
    fun `returning from settings with the grant confirms it`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted)
        val model = viewModel(usage = usage)
        model.openUsageAccessSettings()

        usage.status = UsageAccessStatus.Granted
        model.onResumed()

        val state = readyState(model)
        assertEquals(UsageAccessStatus.Granted, state.setup.usageAccess)
        assertEquals(R.string.applock_setup_usage_access_granted_notice, state.noticeRes)
        assertTrue(state.setup.isReady)
    }

    @Test
    fun `returning without granting produces no notice and no error`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted)
        val model = viewModel(usage = usage)
        model.openUsageAccessSettings()

        model.onResumed()

        val state = readyState(model)
        assertEquals(UsageAccessStatus.NotGranted, state.setup.usageAccess)
        assertNull(state.noticeRes)
        assertNull(state.failure)
    }

    @Test
    fun `the confirmation is shown once and not by the next resume`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted)
        val model = viewModel(usage = usage)
        model.openUsageAccessSettings()
        usage.status = UsageAccessStatus.Granted
        model.onResumed()
        assertNotNull(readyState(model).noticeRes)

        model.onResumed()

        assertNull(readyState(model).noticeRes)
    }

    @Test
    fun `a settings screen that cannot be opened is reported generically and changes nothing`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted).apply {
            openSettingsResult = NivaraResult.Failure()
        }
        val model = viewModel(usage = usage)

        model.openUsageAccessSettings()

        val state = readyState(model)
        assertEquals(R.string.applock_setup_settings_unavailable, state.failure?.textRes)
        assertEquals(UsageAccessStatus.NotGranted, state.setup.usageAccess)
        assertFalse(state.busy)

        // A failed attempt is not a return from settings, so nothing is confirmed later either.
        model.onResumed()
        assertNull(readyState(model).noticeRes)
    }

    @Test
    fun `retrying clears a previous failure`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted).apply {
            openSettingsResult = NivaraResult.Failure()
        }
        val model = viewModel(usage = usage)
        model.openUsageAccessSettings()
        assertNotNull(readyState(model).failure)

        model.refresh()

        val state = readyState(model)
        assertNull(state.failure)
        assertNull(state.noticeRes)
    }

    @Test
    fun `the settings action is marked busy while it is being opened`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted)
        val gate = CompletableDeferred<Unit>()
        usage.openSettingsGate = gate
        val model = viewModel(usage = usage)

        model.openUsageAccessSettings()

        assertTrue(readyState(model).busy)
        assertEquals(1, usage.openSettingsCalls)

        // Let the held call finish so nothing is left suspended when the test ends. Clearing the
        // flag is covered by the tests above, which use a repository that answers immediately.
        gate.complete(Unit)
    }

    private fun viewModel(
        repository: ApplicationRepository = FakeApplicationRepository(NivaraResult.Success(applications)),
        usage: UsageAccessRepository = FakeUsageAccessRepository(UsageAccessStatus.NotGranted),
    ): AppLockSetupViewModel = AppLockSetupViewModel(
        applicationRepository = repository,
        usageAccessRepository = usage,
    )

    private fun readyState(viewModel: AppLockSetupViewModel): AppLockSetupUiState.Ready {
        val state = viewModel.uiState.value
        require(state is AppLockSetupUiState.Ready) { "the screen should be ready but was $state" }
        return state
    }

    private class FakeApplicationRepository(
        var result: NivaraResult<List<InstalledApplication>>,
        var thrown: Exception? = null,
    ) : ApplicationRepository {

        var calls = 0

        override suspend fun installedApplications(): NivaraResult<List<InstalledApplication>> {
            calls++
            thrown?.let { error -> throw error }
            return result
        }
    }

    private class FakeUsageAccessRepository(
        var status: UsageAccessStatus,
        var openSettingsResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
        var openSettingsGate: CompletableDeferred<Unit>? = null,
    ) : UsageAccessRepository {

        var statusCalls = 0
        var openSettingsCalls = 0

        override suspend fun status(): UsageAccessStatus {
            statusCalls++
            return status
        }

        override suspend fun openSettings(): NivaraResult<Unit> {
            openSettingsCalls++
            openSettingsGate?.await()
            return openSettingsResult
        }
    }

    private companion object {
        val applications = listOf(
            InstalledApplication("com.example.camera", "Camera"),
            InstalledApplication("com.example.notes", "Notes"),
        )
    }
}
