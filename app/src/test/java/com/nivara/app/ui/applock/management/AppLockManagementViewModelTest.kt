package com.nivara.app.ui.applock.management

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockState
import com.nivara.app.domain.applock.ApplicationProtectionState
import com.nivara.app.domain.applock.DetectionUnavailability
import com.nivara.app.domain.applock.ProtectedApplication
import com.nivara.app.domain.applock.ProtectedApplicationRepository
import com.nivara.app.domain.applock.ProtectionDecision
import com.nivara.app.domain.applock.ProtectionEvent
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.testing.testSessionManager
import com.nivara.app.ui.applock.ProtectionRunState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local JVM tests for the App Lock management screen's state machine.
 *
 * Everything here runs against fakes of the *interfaces*: no test discovers an application, reads a
 * permission, touches a file or authenticates anybody, and none of them claims to. What is verified
 * is the logic a device cannot be asked about cheaply — which applications are listed and in what
 * order, what each row is allowed to claim, that every change goes through the repository and comes
 * back from it, that a missing capability is never drawn as protection, that an unreadable
 * configuration never becomes an empty one, and that a change requires the existing session gate.
 *
 * The session manager is the production one. The tests establish, expire and lock sessions through
 * its own API, so the policy under test is the policy that ships rather than a second one written
 * for the suite.
 *
 * Whether Android actually returns the launcher list, reports a grant or stores the file is only
 * knowable on a device. Those questions are covered by compiled-but-not-executed instrumented
 * suites and by the repository's own JVM tests, and no claim is made here about them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockManagementViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    /** The test's clock. Moving it forward is how a session expires. */
    private var nowMillis: Long = 1_000L
    private val clock = TimeProvider { nowMillis }

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which only exists on Android.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ reading the list

    @Test
    fun `the screen loads the device's applications and the stored set`() = runTest {
        val protected = FakeProtectedApplicationRepository(initial = setOf("com.example.camera"))

        val state = readyState(viewModel(protected = protected))

        assertEquals(
            "every discovered application is listed",
            listOf("com.example.alarm", "com.example.camera", "com.example.notes"),
            state.rows.map { it.packageName },
        )
        assertEquals(3, state.discoveredCount)
        assertEquals(1, state.protectedCount)
        assertEquals(0, state.protectedNotInstalledCount)
        assertNull(state.emptiness)
    }

    @Test
    fun `an application in the stored set is drawn as protected`() = runTest {
        val model = viewModel(protected = FakeProtectedApplicationRepository(setOf("com.example.camera")))

        val row = readyState(model).rows.single { it.packageName == "com.example.camera" }

        assertEquals(ApplicationProtectionState.Protected, row.state)
        assertEquals("Camera", row.label)
    }

    @Test
    fun `an application outside the stored set is drawn as unprotected`() = runTest {
        val model = viewModel(protected = FakeProtectedApplicationRepository(setOf("com.example.camera")))

        val row = readyState(model).rows.single { it.packageName == "com.example.notes" }

        assertEquals(ApplicationProtectionState.NotProtected, row.state)
    }

    @Test
    fun `before the first answer arrives the screen is loading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue)).apply {
            readGate = gate
        }

        val model = viewModel(repository = repository)

        assertEquals(AppLockManagementUiState.Loading, model.uiState.value)

        gate.complete(Unit)
        assertTrue(model.uiState.value is AppLockManagementUiState.Ready)
    }

    @Test
    fun `a device with nothing to launch reads as empty rather than as an error`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Success(emptyList())))

        val state = readyState(model)

        assertEquals(0, state.discoveredCount)
        assertTrue(state.rows.isEmpty())
        assertEquals(AppLockListEmptiness.DeviceHasNoApplications, state.emptiness)
    }

    @Test
    fun `a discovery failure before anything was shown is the retryable error state`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Failure()))

        assertEquals(AppLockManagementUiState.Error, model.uiState.value)
    }

    @Test
    fun `a discovery failure after a successful read keeps the list and says what happened`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val model = viewModel(repository = repository)
        assertEquals(3, readyState(model).rows.size)

        repository.result = NivaraResult.Failure()
        model.refresh()

        val state = readyState(model)
        assertEquals("a failed refresh is not evidence that the device changed", 3, state.rows.size)
        assertEquals(R.string.applock_manage_error_discovery, state.failure?.textRes)
    }

    @Test
    fun `a repository that throws becomes the error state instead of a crash`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue)).apply {
            thrown = IllegalStateException("the platform failed")
        }

        val model = viewModel(repository = repository)

        assertEquals(AppLockManagementUiState.Error, model.uiState.value)
    }

    @Test
    fun `a stored application that is not installed is counted and kept, not pruned`() = runTest {
        val protected = FakeProtectedApplicationRepository(setOf("com.example.camera", "com.example.gone"))

        val model = viewModel(protected = protected)

        val state = readyState(model)
        assertEquals(1, state.protectedNotInstalledCount)
        assertEquals(
            "the stored set is untouched by what the device currently has",
            setOf("com.example.camera", "com.example.gone"),
            protected.stored,
        )
    }

    // ------------------------------------------------------------------ the unreadable set

    @Test
    fun `an unreadable stored set claims nothing about any application`() = runTest {
        val protected = FakeProtectedApplicationRepository(readable = false)

        val state = readyState(viewModel(protected = protected))

        assertTrue(state.storedSetUnreadable)
        assertTrue("no row may claim anything", state.rows.all { it.state == null })
    }

    @Test
    fun `the protected count is never invented from an unreadable set`() = runTest {
        val protected = FakeProtectedApplicationRepository(initial = setOf("com.example.camera"), readable = false)

        val state = readyState(viewModel(protected = protected))

        assertTrue(state.storedSetUnreadable)
        assertEquals("a count derived from an unreadable set would be invented", 0, state.protectedCount)
        assertNull("reading the list is not a failed action", state.failure)
    }

    @Test
    fun `the protected section cannot list an unreadable set`() = runTest {
        val model = viewModel(protected = FakeProtectedApplicationRepository(readable = false))

        model.onSectionChange(ApplicationSection.Protected)

        val state = readyState(model)
        assertTrue(state.rows.isEmpty())
        assertEquals(AppLockListEmptiness.ProtectedSetUnreadable, state.emptiness)
    }

    @Test
    fun `a change is refused while the protected set cannot be read`() = runTest {
        val protected = FakeProtectedApplicationRepository(readable = false)
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.protect(camera)

        assertEquals("nothing is written to a configuration that cannot be read", 0, protected.protectCalls)
        assertEquals(R.string.applock_manage_error_protected_set, failureRes(readyState(model)))
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `an empty query shows every application`() = runTest {
        val model = viewModel()

        assertEquals(3, readyState(model).rows.size)
    }

    @Test
    fun `searching matches a label case-insensitively`() = runTest {
        val model = viewModel()

        model.onQueryChange("cam")

        val state = readyState(model)
        assertEquals(listOf("com.example.camera"), state.rows.map { it.packageName })
        assertEquals("cam", state.query)
    }

    @Test
    fun `searching matches the package name, as the domain's search contract allows`() = runTest {
        val model = viewModel()

        model.onQueryChange("notes")

        assertEquals(listOf("com.example.notes"), readyState(model).rows.map { it.packageName })
        assertEquals("notes", readyState(model).query)
    }

    @Test
    fun `a query that matches nothing says so instead of looking like an empty device`() = runTest {
        val model = viewModel()

        model.onQueryChange("zzz")

        val state = readyState(model)
        assertTrue(state.rows.isEmpty())
        assertEquals(3, state.discoveredCount)
        assertEquals(AppLockListEmptiness.NoSearchResults, state.emptiness)
    }

    @Test
    fun `clearing the query restores the whole list`() = runTest {
        val model = viewModel()
        model.onQueryChange("cam")
        assertEquals(1, readyState(model).rows.size)

        model.onQueryChange("")

        assertEquals(3, readyState(model).rows.size)
        assertNull(readyState(model).emptiness)
    }

    @Test
    fun `searching writes nothing and does not re-read the device`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val protected = FakeProtectedApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(repository = repository, protected = protected)

        val readsBefore = repository.calls
        model.onQueryChange("cam")
        model.onQueryChange("zzz")
        model.onQueryChange("")

        assertEquals("filtering is not a repository operation", readsBefore, repository.calls)
        assertEquals(0, protected.protectCalls)
        assertEquals(0, protected.unprotectCalls)
    }

    @Test
    fun `searching keeps the section's own filter`() = runTest {
        val model = viewModel(protected = FakeProtectedApplicationRepository(setOf("com.example.camera")))
        model.onSectionChange(ApplicationSection.Protected)

        model.onQueryChange("notes")

        val state = readyState(model)
        assertTrue("an unprotected application cannot appear in the protected section", state.rows.isEmpty())
        assertEquals(AppLockListEmptiness.NoSearchResults, state.emptiness)
    }

    // ------------------------------------------------------------------ sorting

    @Test
    fun `the default order is the domain's ascending order`() = runTest {
        val model = viewModel()

        assertEquals(
            listOf("Alarm", "Camera", "Notes"),
            readyState(model).rows.map { it.label },
        )
    }

    @Test
    fun `the reverse order is the domain's descending order`() = runTest {
        val model = viewModel()

        model.onSortChange(ApplicationSortOrder.NameDescending)

        val state = readyState(model)
        assertEquals(listOf("Notes", "Camera", "Alarm"), state.rows.map { it.label })
        assertEquals(ApplicationSortOrder.NameDescending, state.sort)
    }

    @Test
    fun `identical labels keep a fixed sequence in both directions`() = runTest {
        val duplicates = listOf(
            InstalledApplication("com.zeta.notes", "Notes"),
            InstalledApplication("com.alpha.notes", "Notes"),
        )
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Success(duplicates)))

        assertEquals(
            listOf("com.alpha.notes", "com.zeta.notes"),
            readyState(model).rows.map { it.packageName },
        )

        model.onSortChange(ApplicationSortOrder.NameDescending)

        assertEquals(
            listOf("com.zeta.notes", "com.alpha.notes"),
            readyState(model).rows.map { it.packageName },
        )
    }

    @Test
    fun `sorting never touches the stored protected set`() = runTest {
        val protected = FakeProtectedApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(protected = protected)

        model.onSortChange(ApplicationSortOrder.NameDescending)
        model.onSortChange(ApplicationSortOrder.NameAscending)

        assertEquals(setOf("com.example.camera"), protected.stored)
        assertEquals(0, protected.protectCalls)
        assertEquals(0, protected.unprotectCalls)
        assertEquals(
            "and the rows still say what the stored set says",
            ApplicationProtectionState.Protected,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.state,
        )
    }

    // ------------------------------------------------------------------ sections

    @Test
    fun `the protected section lists only the stored set`() = runTest {
        val model = viewModel(
            protected = FakeProtectedApplicationRepository(setOf("com.example.camera", "com.example.alarm")),
        )

        model.onSectionChange(ApplicationSection.Protected)

        val state = readyState(model)
        assertEquals(listOf("Alarm", "Camera"), state.rows.map { it.label })
        assertEquals(ApplicationSection.Protected, state.section)
    }

    @Test
    fun `a protected section with nothing protected says so`() = runTest {
        val model = viewModel()

        model.onSectionChange(ApplicationSection.Protected)

        val state = readyState(model)
        assertTrue(state.rows.isEmpty())
        assertEquals(AppLockListEmptiness.NothingProtected, state.emptiness)
        assertEquals(3, state.discoveredCount)
    }

    // ------------------------------------------------------------------ protecting and unprotecting

    @Test
    fun `protecting writes through the repository and shows what is stored`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.protect(camera)

        assertEquals(listOf("com.example.camera"), protected.protectCalls.map { it.packageName })
        assertEquals(setOf("com.example.camera"), protected.stored)
        assertEquals(
            ApplicationProtectionState.Protected,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.state,
        )
        assertEquals(R.string.applock_manage_notice_changed, readyState(model).noticeRes)
        assertNull(readyState(model).failure)
    }

    @Test
    fun `unprotecting writes through the repository and shows what is stored`() = runTest {
        val protected = FakeProtectedApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.unprotect(camera)

        assertEquals(listOf("com.example.camera"), protected.unprotectCalls.map { it.packageName })
        assertTrue(protected.stored.isEmpty())
        assertEquals(
            ApplicationProtectionState.NotProtected,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.state,
        )
    }

    @Test
    fun `protecting twice stores one application, not two`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.protect(camera)
        model.protect(camera)

        assertEquals(2, protected.protectCalls.size)
        assertEquals("a repeated protect is idempotent", setOf("com.example.camera"), protected.stored)
    }

    @Test
    fun `unprotecting twice is idempotent too`() = runTest {
        val protected = FakeProtectedApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.unprotect(camera)
        model.unprotect(camera)

        assertEquals(2, protected.unprotectCalls.size)
        assertTrue(protected.stored.isEmpty())
        assertNull(readyState(model).failure)
    }

    @Test
    fun `the state comes back from the repository, never from the tap`() = runTest {
        // The repository accepts the write and then still reports nothing protected: the screen
        // must show that, rather than the change it asked for.
        val protected = FakeProtectedApplicationRepository().apply {
            protectResult = NivaraResult.Success(Unit)
            writesAreIgnored = true
        }
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.protect(camera)

        assertEquals(
            ApplicationProtectionState.NotProtected,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.state,
        )
    }

    @Test
    fun `a write the repository refuses is reported and changes nothing`() = runTest {
        val protected = FakeProtectedApplicationRepository().apply {
            protectResult = NivaraResult.Failure()
        }
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.protect(camera)

        val state = readyState(model)
        assertEquals(R.string.applock_manage_error_change_not_stored, failureRes(state))
        assertNull(state.noticeRes)
        assertTrue("nothing was half-written", protected.stored.isEmpty())
        assertEquals(
            ApplicationProtectionState.NotProtected,
            state.rows.single { it.packageName == "com.example.camera" }.state,
        )
    }

    @Test
    fun `a change for an application that is gone writes nothing and refreshes the list`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val model = viewModel(repository = repository, protected = protected, session = authenticatedSession())
        val readsBefore = repository.calls

        model.protect(InstalledApplication("com.example.gone", "Gone"))

        val state = readyState(model)
        assertEquals(0, protected.protectCalls.size)
        assertEquals(R.string.applock_manage_error_not_installed, failureRes(state))
        assertTrue("the stale row is re-read away", repository.calls > readsBefore)
    }

    @Test
    fun `an uninstalled application cannot be unprotected either`() = runTest {
        val protected = FakeProtectedApplicationRepository(setOf("com.example.gone"))
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.unprotect(InstalledApplication("com.example.gone", "Gone"))

        assertEquals(0, protected.unprotectCalls.size)
        assertEquals(R.string.applock_manage_error_not_installed, failureRes(readyState(model)))
    }

    @Test
    fun `a second change is refused while the first is being stored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val protected = FakeProtectedApplicationRepository().apply { writeGate = gate }
        val model = viewModel(protected = protected, session = authenticatedSession())

        model.protect(camera)
        assertTrue(readyState(model).busy)

        model.protect(notes)

        assertEquals("one change at a time", listOf("com.example.camera"), protected.protectCalls.map { it.packageName })

        // Let the held write finish so nothing is left suspended when the test ends. The flag
        // clearing itself is covered by the tests above, which use a repository that answers
        // immediately.
        gate.complete(Unit)
    }

    // ------------------------------------------------------------------ the session gate

    @Test
    fun `reading the list does not require a session`() = runTest {
        val model = viewModel(session = testSessionManager(clock))

        val state = readyState(model)
        assertEquals(3, state.rows.size)
        assertFalse(state.sessionAuthenticated)
        assertNull(state.failure)
    }

    @Test
    fun `a change without a session is refused and writes nothing`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val model = viewModel(protected = protected, session = testSessionManager(clock))

        model.protect(camera)

        assertEquals(0, protected.protectCalls.size)
        assertEquals(R.string.applock_manage_error_locked, failureRes(readyState(model)))
    }

    @Test
    fun `a change is allowed once the existing gate has opened`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(protected = protected, session = session)
        model.protect(camera)
        assertEquals(0, protected.protectCalls.size)

        session.establish(AuthenticationOutcome.Succeeded)
        model.protect(camera)

        assertEquals(1, protected.protectCalls.size)
        assertTrue(readyState(model).sessionAuthenticated)
    }

    @Test
    fun `a failed authentication does not open the gate`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(protected = protected, session = session)

        session.establish(AuthenticationOutcome.Failed(blockedForMillis = 0L))
        model.protect(camera)

        assertEquals(0, protected.protectCalls.size)
        assertEquals(R.string.applock_manage_error_locked, failureRes(readyState(model)))
    }

    @Test
    fun `a change is refused once the session has expired`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(protected = protected, session = session)
        session.establish(AuthenticationOutcome.Succeeded)

        nowMillis += TEST_TIMEOUT_MILLIS
        model.protect(camera)

        assertEquals("an expired session authorizes nothing", 0, protected.protectCalls.size)
        assertFalse(readyState(model).sessionAuthenticated)
    }

    @Test
    fun `quick lock closes the gate for changes`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(protected = protected, session = session)
        session.establish(AuthenticationOutcome.Succeeded)

        session.lockNow()
        model.protect(camera)

        assertEquals(0, protected.protectCalls.size)
        assertEquals(R.string.applock_manage_error_locked, failureRes(readyState(model)))
        assertFalse(readyState(model).sessionAuthenticated)
    }

    @Test
    fun `the screen notices the gate closing while it is open`() = runTest {
        val session = testSessionManager(clock)
        val model = viewModel(session = session)
        session.establish(AuthenticationOutcome.Succeeded)
        assertTrue(readyState(model).sessionAuthenticated)

        session.lockNow()

        assertFalse("the controls must not stay enabled", readyState(model).sessionAuthenticated)
    }

    @Test
    fun `a refusal is reported again if the user tries again while still locked`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val model = viewModel(protected = protected, session = testSessionManager(clock))

        model.protect(camera)
        model.protect(camera)

        assertEquals(0, protected.protectCalls.size)
        assertEquals(R.string.applock_manage_error_locked, failureRes(readyState(model)))
    }

    // ------------------------------------------------------------------ capabilities and readiness

    @Test
    fun `missing capabilities are listed, and protection is shown as unavailable`() = runTest {
        val model = viewModel(
            protected = FakeProtectedApplicationRepository(setOf("com.example.camera")),
            usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted),
            overlay = FakeOverlayCapabilityRepository(OverlayCapability.NotGranted),
        )

        val state = readyState(model)
        assertEquals(
            listOf(AppLockPrerequisite.UsageAccess, AppLockPrerequisite.Overlay),
            state.missingPrerequisites,
        )
        assertFalse(state.capabilitiesReady)
        assertEquals(
            "the stored decision is visible, and so is the fact that it cannot be applied",
            ApplicationProtectionState.ProtectedButUnavailable,
            state.rows.single { it.packageName == "com.example.camera" }.state,
        )
    }

    @Test
    fun `a capability that cannot be read is a missing prerequisite, never a grant`() = runTest {
        val model = viewModel(
            overlay = FakeOverlayCapabilityRepository(OverlayCapability.Unavailable),
            usage = FakeUsageAccessRepository(UsageAccessStatus.Unavailable),
        )

        val state = readyState(model)
        assertFalse(state.capabilitiesReady)
        assertEquals(
            listOf(AppLockPrerequisite.UsageAccess, AppLockPrerequisite.Overlay),
            state.missingPrerequisites,
        )
    }

    @Test
    fun `a missing capability never turns a protected application into an unprotected one`() = runTest {
        val model = viewModel(
            protected = FakeProtectedApplicationRepository(setOf("com.example.camera")),
            usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted),
        )

        val states = readyState(model).rows.associate { it.packageName to it.state }

        assertEquals(ApplicationProtectionState.ProtectedButUnavailable, states["com.example.camera"])
        assertEquals(ApplicationProtectionState.NotProtected, states["com.example.notes"])
    }

    @Test
    fun `returning from Android's settings picks up a new capability`() = runTest {
        val usage = FakeUsageAccessRepository(UsageAccessStatus.NotGranted)
        val model = viewModel(
            protected = FakeProtectedApplicationRepository(setOf("com.example.camera")),
            usage = usage,
        )
        assertEquals(listOf(AppLockPrerequisite.UsageAccess), readyState(model).missingPrerequisites)

        usage.status = UsageAccessStatus.Granted
        model.onResumed()

        val state = readyState(model)
        assertTrue(state.capabilitiesReady)
        assertEquals(
            ApplicationProtectionState.Protected,
            state.rows.single { it.packageName == "com.example.camera" }.state,
        )
    }

    @Test
    fun `the run state is taken from the component that owns protection`() = runTest {
        val monitor = FakeMonitor()
        val model = viewModel(monitor = monitor)
        assertEquals(ProtectionRunState.Stopped, readyState(model).runState)

        monitor.state.value = AppLockState.Monitoring(
            foreground = null,
            decision = ProtectionDecision.NoProtectionRequired,
        )
        assertEquals(ProtectionRunState.Running, readyState(model).runState)

        monitor.state.value = AppLockState.Unavailable(DetectionUnavailability.UsageAccessNotGranted)
        assertEquals(ProtectionRunState.WithoutDecision, readyState(model).runState)
    }

    // ------------------------------------------------------------------ refreshing

    @Test
    fun `resuming re-reads the device and the stored set`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val protected = FakeProtectedApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(repository = repository, protected = protected)
        val readsBefore = repository.calls
        val protectedReadsBefore = protected.readCalls

        model.onResumed()

        assertEquals(readsBefore + 1, repository.calls)
        assertEquals(protectedReadsBefore + 1, protected.readCalls)
    }

    @Test
    fun `a resume picks up an application that appeared while the screen was away`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val model = viewModel(repository = repository)
        assertEquals(3, readyState(model).rows.size)

        repository.result = NivaraResult.Success(catalogue + maps)
        model.onResumed()

        assertEquals(4, readyState(model).rows.size)
    }

    @Test
    fun `a resume picks up a change made outside this screen`() = runTest {
        val protected = FakeProtectedApplicationRepository()
        val model = viewModel(protected = protected)
        assertEquals(
            ApplicationProtectionState.NotProtected,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.state,
        )

        protected.stored = setOf("com.example.camera")
        model.onResumed()

        assertEquals(
            "the stored set stays the only source of truth",
            ApplicationProtectionState.Protected,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.state,
        )
    }

    @Test
    fun `a resume keeps the query, the section and the order the user chose`() = runTest {
        val model = viewModel(protected = FakeProtectedApplicationRepository(setOf("com.example.camera")))
        model.onQueryChange("a")
        model.onSectionChange(ApplicationSection.Protected)
        model.onSortChange(ApplicationSortOrder.NameDescending)

        model.onResumed()

        val state = readyState(model)
        assertEquals("a", state.query)
        assertEquals(ApplicationSection.Protected, state.section)
        assertEquals(ApplicationSortOrder.NameDescending, state.sort)
    }

    @Test
    fun `retrying from the error state loads again`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Failure())
        val model = viewModel(repository = repository)
        assertEquals(AppLockManagementUiState.Error, model.uiState.value)

        repository.result = NivaraResult.Success(catalogue)
        model.refresh()

        assertEquals(3, readyState(model).rows.size)
    }

    // ------------------------------------------------------------------ helpers

    private fun authenticatedSession(): SessionManager =
        testSessionManager(clock).also { session -> session.establish(AuthenticationOutcome.Succeeded) }

    private fun viewModel(
        repository: ApplicationRepository = FakeApplicationRepository(NivaraResult.Success(catalogue)),
        protected: FakeProtectedApplicationRepository = FakeProtectedApplicationRepository(),
        usage: UsageAccessRepository = FakeUsageAccessRepository(UsageAccessStatus.Granted),
        overlay: OverlayCapabilityRepository = FakeOverlayCapabilityRepository(OverlayCapability.Granted),
        session: SessionManager = authenticatedSession(),
        monitor: AppLockMonitor = FakeMonitor(),
    ): AppLockManagementViewModel = AppLockManagementViewModel(
        applicationRepository = repository,
        protectedApplicationRepository = protected,
        usageAccessRepository = usage,
        overlayCapabilityRepository = overlay,
        sessionManager = session,
        monitor = monitor,
    )

    private fun readyState(viewModel: AppLockManagementViewModel): AppLockManagementUiState.Ready {
        val state = viewModel.uiState.value
        require(state is AppLockManagementUiState.Ready) { "the screen should be ready but was $state" }
        return state
    }

    /** The resource of the failure a ready state is showing, or `null` when it shows none. */
    private fun failureRes(state: AppLockManagementUiState.Ready): Int? = state.failure?.textRes

    private class FakeApplicationRepository(
        var result: NivaraResult<List<InstalledApplication>>,
        var thrown: Exception? = null,
    ) : ApplicationRepository {

        var calls = 0
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun installedApplications(): NivaraResult<List<InstalledApplication>> {
            calls++
            readGate?.await()
            thrown?.let { error -> throw error }
            return result
        }
    }

    private class FakeProtectedApplicationRepository(
        initial: Set<String> = emptySet(),
        var readable: Boolean = true,
    ) : ProtectedApplicationRepository {

        var stored: Set<String> = initial
        var readCalls = 0
        var protectCalls = mutableListOf<ProtectedApplication>()
        var unprotectCalls = mutableListOf<ProtectedApplication>()
        var protectResult: NivaraResult<Unit> = NivaraResult.Success(Unit)
        var unprotectResult: NivaraResult<Unit> = NivaraResult.Success(Unit)

        /** When true the write is accepted but the stored set does not change, as a broken store would. */
        var writesAreIgnored = false

        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun protectedApplications(): NivaraResult<Set<ProtectedApplication>> {
            readCalls++
            if (!readable) return NivaraResult.Failure()
            return NivaraResult.Success(stored.mapTo(mutableSetOf()) { name -> ProtectedApplication(name) })
        }

        override suspend fun protect(application: ProtectedApplication): NivaraResult<Unit> {
            protectCalls += application
            writeGate?.await()
            if (protectResult is NivaraResult.Success && !writesAreIgnored) {
                stored = stored + application.packageName
            }
            return protectResult
        }

        override suspend fun unprotect(application: ProtectedApplication): NivaraResult<Unit> {
            unprotectCalls += application
            writeGate?.await()
            if (unprotectResult is NivaraResult.Success && !writesAreIgnored) {
                stored = stored - application.packageName
            }
            return unprotectResult
        }
    }

    private class FakeUsageAccessRepository(var status: UsageAccessStatus) : UsageAccessRepository {

        override suspend fun status(): UsageAccessStatus = status

        override suspend fun openSettings(): NivaraResult<Unit> = NivaraResult.Success(Unit)
    }

    private class FakeOverlayCapabilityRepository(private var capability: OverlayCapability) :
        OverlayCapabilityRepository {

        override suspend fun status(): OverlayCapability = capability

        override suspend fun openSettings(): NivaraResult<Unit> = NivaraResult.Success(Unit)
    }

    private class FakeMonitor : AppLockMonitor {

        override val state = MutableStateFlow<AppLockState>(AppLockState.Stopped)

        override val events: SharedFlow<ProtectionEvent> = MutableSharedFlow(extraBufferCapacity = 4)

        override fun start() = Unit

        override fun stop() = Unit
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 60_000L

        val camera = InstalledApplication("com.example.camera", "Camera")
        val notes = InstalledApplication("com.example.notes", "Notes")
        val maps = InstalledApplication("com.example.maps", "Maps")

        val catalogue = listOf(
            InstalledApplication("com.example.notes", "Notes"),
            InstalledApplication("com.example.camera", "Camera"),
            InstalledApplication("com.example.alarm", "Alarm"),
        )
    }
}
