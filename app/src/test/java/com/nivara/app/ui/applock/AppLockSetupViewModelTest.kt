package com.nivara.app.ui.applock

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.app.ApplicationDiscoveryState
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockProtectionRunner
import com.nivara.app.domain.applock.AppLockState
import com.nivara.app.domain.applock.DetectionUnavailability
import com.nivara.app.domain.applock.ProtectionDecision
import com.nivara.app.domain.applock.ProtectionEvent
import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
 * return from those settings is noticed, that nothing throws when a repository misbehaves, and that
 * the protection switch only asks the component that owns protection — and then reports what that
 * component says rather than what the tap assumed.
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

    // ------------------------------------------------------------------ overlay and protection

    @Test
    fun `the overlay capability is loaded into the ready state`() = runTest {
        val model = viewModel(overlay = FakeOverlayCapabilityRepository(OverlayCapability.NotGranted))

        val state = readyState(model)

        assertEquals(OverlayCapability.NotGranted, state.setup.overlay)
        assertEquals(listOf(AppLockPrerequisite.Overlay), state.setup.missingPrerequisites)
    }

    @Test
    fun `an unreadable overlay capability is a missing prerequisite, never a grant`() = runTest {
        val model = viewModel(overlay = FakeOverlayCapabilityRepository(OverlayCapability.Unavailable))

        assertFalse(readyState(model).setup.isReady)
    }

    @Test
    fun `opening the overlay settings is not a grant`() = runTest {
        val overlay = FakeOverlayCapabilityRepository(OverlayCapability.NotGranted)
        val model = viewModel(overlay = overlay)

        model.openOverlaySettings()
        model.onResumed()

        assertEquals(1, overlay.openSettingsCalls)
        assertEquals(OverlayCapability.NotGranted, readyState(model).setup.overlay)
        assertNull("opening a screen is not a grant notice either", readyState(model).noticeRes)
    }

    @Test
    fun `a returned overlay grant is confirmed once`() = runTest {
        val overlay = FakeOverlayCapabilityRepository(OverlayCapability.NotGranted)
        val model = viewModel(overlay = overlay)

        model.openOverlaySettings()
        overlay.capability = OverlayCapability.Granted
        model.onResumed()

        assertEquals(R.string.applock_setup_overlay_granted_notice, readyState(model).noticeRes)

        model.onResumed()
        assertNull("the confirmation is one-shot", readyState(model).noticeRes)
    }

    @Test
    fun `a failed settings screen is reported, and nothing is claimed about the grant`() = runTest {
        val overlay = FakeOverlayCapabilityRepository(
            capability = OverlayCapability.NotGranted,
            openSettingsResult = NivaraResult.Failure(),
        )
        val model = viewModel(overlay = overlay)

        model.openOverlaySettings()

        assertNotNull(readyState(model).failure)
        assertEquals(OverlayCapability.NotGranted, readyState(model).setup.overlay)
    }

    @Test
    fun `protection is off until it is asked for`() = runTest {
        val runner = FakeProtectionRunner()
        val model = viewModel(runner = runner)

        assertEquals(ProtectionRunState.Stopped, readyState(model).protection)
        assertEquals(0, runner.startCalls)
    }

    @Test
    fun `turning protection on asks the runner exactly once`() = runTest {
        val runner = FakeProtectionRunner()
        val usage = FakeUsageAccessRepository(UsageAccessStatus.Granted)
        val model = viewModel(runner = runner, usage = usage)

        model.startProtection()

        assertEquals(1, runner.startCalls)
        assertEquals(0, runner.stopCalls)
    }

    @Test
    fun `a start the device refuses is reported, and protection is not claimed`() = runTest {
        val runner = FakeProtectionRunner(startResult = NivaraResult.Failure())
        val model = viewModel(runner = runner, usage = FakeUsageAccessRepository(UsageAccessStatus.Granted))

        model.startProtection()

        assertEquals(1, runner.startCalls)
        assertNotNull("a refusal must not be silent", readyState(model).failure)
        assertEquals(
            "and the switch still shows what the component reports",
            ProtectionRunState.Stopped,
            readyState(model).protection,
        )
    }

    @Test
    fun `a stop the device refuses is reported too`() = runTest {
        val runner = FakeProtectionRunner(stopResult = NivaraResult.Failure())
        val model = viewModel(runner = runner)

        model.stopProtection()

        assertEquals(1, runner.stopCalls)
        assertNotNull(readyState(model).failure)
    }

    @Test
    fun `protection cannot be turned on while a prerequisite is missing`() = runTest {
        val runner = FakeProtectionRunner()
        val model = viewModel(runner = runner, usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted))

        model.startProtection()

        assertEquals("an unprepared switch must not start anything", 0, runner.startCalls)
    }

    @Test
    fun `the running state is read back from the component that owns protection`() = runTest {
        val monitor = FakeMonitor()
        val model = viewModel(monitor = monitor)
        assertEquals(ProtectionRunState.Stopped, readyState(model).protection)

        monitor.state.value = AppLockState.Monitoring(
            foreground = null,
            decision = ProtectionDecision.NoProtectionRequired,
        )

        assertEquals(ProtectionRunState.Running, readyState(model).protection)
    }

    @Test
    fun `detection that cannot decide is reported as itself`() = runTest {
        val monitor = FakeMonitor()
        val model = viewModel(monitor = monitor)

        monitor.state.value = AppLockState.Unavailable(
            DetectionUnavailability.UsageAccessNotGranted,
        )

        assertEquals(ProtectionRunState.WithoutDecision, readyState(model).protection)
    }

    @Test
    fun `turning protection off is always allowed`() = runTest {
        val runner = FakeProtectionRunner()
        val monitor = FakeMonitor()
        val model = viewModel(
            runner = runner,
            monitor = monitor,
            usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted),
        )
        monitor.state.value = AppLockState.Unavailable(
            DetectionUnavailability.UsageAccessNotGranted,
        )

        model.stopProtection()

        assertEquals(1, runner.stopCalls)
    }

    private fun viewModel(
        repository: ApplicationRepository = FakeApplicationRepository(NivaraResult.Success(applications)),
        usage: UsageAccessRepository = FakeUsageAccessRepository(UsageAccessStatus.NotGranted),
        overlay: OverlayCapabilityRepository = FakeOverlayCapabilityRepository(OverlayCapability.Granted),
        runner: AppLockProtectionRunner = FakeProtectionRunner(),
        monitor: AppLockMonitor = FakeMonitor(),
    ): AppLockSetupViewModel = AppLockSetupViewModel(
        applicationRepository = repository,
        usageAccessRepository = usage,
        overlayCapabilityRepository = overlay,
        protectionRunner = runner,
        monitor = monitor,
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

    private class FakeOverlayCapabilityRepository(
        var capability: OverlayCapability,
        var openSettingsResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
    ) : OverlayCapabilityRepository {

        var statusCalls = 0
        var openSettingsCalls = 0

        override suspend fun status(): OverlayCapability {
            statusCalls++
            return capability
        }

        override suspend fun openSettings(): NivaraResult<Unit> {
            openSettingsCalls++
            return openSettingsResult
        }
    }

    private class FakeProtectionRunner(
        var startResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
        var stopResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
    ) : AppLockProtectionRunner {

        var startCalls = 0
        var stopCalls = 0

        override fun start(): NivaraResult<Unit> {
            startCalls++
            return startResult
        }

        override fun stop(): NivaraResult<Unit> {
            stopCalls++
            return stopResult
        }
    }

    private class FakeMonitor : AppLockMonitor {

        override val state = MutableStateFlow<AppLockState>(AppLockState.Stopped)

        override val events: SharedFlow<ProtectionEvent> = MutableSharedFlow(extraBufferCapacity = 4)

        override fun start() = Unit

        override fun stop() = Unit
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
