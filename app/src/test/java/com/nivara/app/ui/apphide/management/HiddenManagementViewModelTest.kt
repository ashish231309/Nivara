package com.nivara.app.ui.apphide.management

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.ApplicationVisibility
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationFailure
import com.nivara.app.domain.apphide.HiddenApplicationRepository
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.testing.testSessionManager
import com.nivara.app.ui.applications.ApplicationSortOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
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
 * Local JVM tests for the hidden-application management screen's state machine.
 *
 * Everything here runs against fakes of the *interfaces*: no test discovers an application, touches
 * a file or authenticates anybody, and none of them claims to. What is verified is the logic a
 * device cannot be asked about cheaply — which applications are listed and in what order, what each
 * row is allowed to claim, that every change goes through the repository and comes back from it,
 * that a hidden set which cannot be read never becomes an empty one, and that a change requires the
 * existing session gate.
 *
 * The session manager is the production one. The tests establish, expire and lock sessions through
 * its own API, so the policy under test is the policy that ships rather than a second one written
 * for the suite.
 *
 * Whether Android actually returns the launcher list or stores the file is only knowable on a
 * device. Those questions are covered by compiled-but-not-executed instrumented suites and by the
 * storage layer's own JVM tests, and no claim is made here about them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenManagementViewModelTest {

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
    fun `the screen loads the device's applications and the stored hidden set`() = runTest {
        val hidden = FakeHiddenApplicationRepository(initial = setOf("com.example.camera"))

        val state = readyState(viewModel(hidden = hidden))

        assertEquals(
            "every discovered application is listed",
            listOf("com.example.alarm", "com.example.camera", "com.example.notes"),
            state.rows.map { it.packageName },
        )
        assertEquals(3, state.discoveredCount)
        assertEquals(1, state.hiddenState.let { (it as HiddenStateAvailability.Available).hiddenCount })
        assertNull(state.emptiness)
    }

    @Test
    fun `an application in the stored set is drawn as hidden`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.camera")))

        val row = readyState(model).rows.single { it.packageName == "com.example.camera" }

        assertEquals(ApplicationVisibility.Hidden, row.visibility)
        assertEquals("Camera", row.label)
    }

    @Test
    fun `an application outside the stored set is drawn as visible`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.camera")))

        val row = readyState(model).rows.single { it.packageName == "com.example.notes" }

        assertEquals(ApplicationVisibility.Visible, row.visibility)
    }

    @Test
    fun `before the first answer arrives the screen is loading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue)).apply { readGate = gate }

        val model = viewModel(repository = repository)

        assertEquals(HiddenManagementUiState.Loading, model.uiState.value)

        gate.complete(Unit)
        assertTrue(model.uiState.value is HiddenManagementUiState.Ready)
    }

    @Test
    fun `a device with nothing to launch reads as empty rather than as an error`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Success(emptyList())))

        val state = readyState(model)

        assertEquals(0, state.discoveredCount)
        assertTrue(state.rows.isEmpty())
        assertEquals(HiddenListEmptiness.DeviceHasNoApplications, state.emptiness)
    }

    @Test
    fun `a discovery failure before anything was shown is the retryable error state`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Failure()))

        assertEquals(HiddenManagementUiState.Error, model.uiState.value)
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
        assertEquals(R.string.apphide_manage_error_discovery, state.failure?.textRes)
    }

    @Test
    fun `a repository that throws becomes the error state instead of a crash`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue)).apply {
            thrown = IllegalStateException("the platform failed")
        }

        val model = viewModel(repository = repository)

        assertEquals(HiddenManagementUiState.Error, model.uiState.value)
    }

    @Test
    fun `a stored application that is not installed is counted and kept, not pruned`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.camera", "com.example.gone"))

        val model = viewModel(hidden = hidden)

        val state = readyState(model)
        assertEquals(
            "a missing application keeps its entry, so it is hidden again if it comes back",
            1,
            (state.hiddenState as HiddenStateAvailability.Available).notInstalledCount,
        )
        assertEquals(setOf("com.example.camera", "com.example.gone"), hidden.stored)
    }

    // ------------------------------------------------------------------ unreadable and unavailable

    @Test
    fun `an unreadable stored set claims nothing about any application`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unreadable))

        val state = readyState(model)
        assertEquals(HiddenStateAvailability.Unreadable, state.hiddenState)
        assertTrue("no row may claim anything", state.rows.all { it.visibility == null })
    }

    @Test
    fun `an unavailable stored set claims nothing either, and is reported as itself`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unavailable))

        val state = readyState(model)
        assertEquals(HiddenStateAvailability.Unavailable, state.hiddenState)
        assertTrue(state.rows.all { it.visibility == null })
    }

    @Test
    fun `an unreadable set is never presented as nothing hidden`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unreadable))

        model.onSectionChange(HiddenSection.Hidden)

        val state = readyState(model)
        assertTrue(state.rows.isEmpty())
        assertEquals(
            "an empty section and an unreadable set are different sentences",
            HiddenListEmptiness.HiddenStateUnreadable,
            state.emptiness,
        )
        assertFalse(state.canChange)
    }

    @Test
    fun `an unavailable set has its own empty message`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unavailable))

        model.onSectionChange(HiddenSection.Hidden)

        assertEquals(HiddenListEmptiness.HiddenStateUnavailable, readyState(model).emptiness)
    }

    @Test
    fun `a change is refused while the stored set cannot be read`() = runTest {
        val hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unreadable)
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.hide(camera)

        assertEquals("nothing is written to a configuration that cannot be read", 0, hidden.hideCalls.size)
        assertEquals(R.string.apphide_manage_error_hidden_unreadable, failureRes(readyState(model)))
    }

    @Test
    fun `a change is refused, with its own message, when the stored set cannot be reached`() = runTest {
        val hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unavailable)
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.unhide(camera)

        assertEquals(0, hidden.unhideCalls.size)
        assertEquals(R.string.apphide_manage_error_hidden_unavailable, failureRes(readyState(model)))
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `an empty query shows every application`() = runTest {
        assertEquals(3, readyState(viewModel()).rows.size)
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
    }

    @Test
    fun `a query that matches nothing says so instead of looking like an empty device`() = runTest {
        val model = viewModel()

        model.onQueryChange("zzz")

        val state = readyState(model)
        assertTrue(state.rows.isEmpty())
        assertEquals(3, state.discoveredCount)
        assertEquals(HiddenListEmptiness.NoSearchResults, state.emptiness)
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
    fun `searching writes nothing and does not re-read`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(repository = repository, hidden = hidden)

        val readsBefore = repository.calls
        model.onQueryChange("cam")
        model.onQueryChange("zzz")
        model.onQueryChange("")

        assertEquals("filtering is not a repository operation", readsBefore, repository.calls)
        assertEquals(0, hidden.hideCalls.size)
        assertEquals(0, hidden.unhideCalls.size)
    }

    @Test
    fun `searching keeps the section's own filter`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.camera")))
        model.onSectionChange(HiddenSection.Hidden)

        model.onQueryChange("notes")

        val state = readyState(model)
        assertTrue("a visible application cannot appear in the hidden section", state.rows.isEmpty())
        assertEquals(HiddenListEmptiness.NoSearchResults, state.emptiness)
    }

    @Test
    fun `the query survives a refresh`() = runTest {
        val model = viewModel()
        model.onQueryChange("cam")

        model.onResumed()

        assertEquals("cam", readyState(model).query)
        assertEquals(listOf("com.example.camera"), readyState(model).rows.map { it.packageName })
    }

    // ------------------------------------------------------------------ sorting

    @Test
    fun `the default order is the domain's ascending order`() = runTest {
        assertEquals(
            listOf("Alarm", "Camera", "Notes"),
            readyState(viewModel()).rows.map { it.label },
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
    fun `sorting never touches the stored hidden set`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(hidden = hidden)

        model.onSortChange(ApplicationSortOrder.NameDescending)
        model.onSortChange(ApplicationSortOrder.NameAscending)

        assertEquals(setOf("com.example.camera"), hidden.stored)
        assertEquals(0, hidden.hideCalls.size)
        assertEquals(0, hidden.unhideCalls.size)
        assertEquals(
            "and the rows still say what the stored set says",
            ApplicationVisibility.Hidden,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.visibility,
        )
    }

    // ------------------------------------------------------------------ sections

    @Test
    fun `the hidden section lists only the stored set`() = runTest {
        val model = viewModel(
            hidden = FakeHiddenApplicationRepository(setOf("com.example.camera", "com.example.alarm")),
        )

        model.onSectionChange(HiddenSection.Hidden)

        val state = readyState(model)
        assertEquals(listOf("Alarm", "Camera"), state.rows.map { it.label })
        assertEquals(HiddenSection.Hidden, state.section)
    }

    @Test
    fun `a hidden section with nothing hidden says so`() = runTest {
        val model = viewModel()

        model.onSectionChange(HiddenSection.Hidden)

        val state = readyState(model)
        assertTrue(state.rows.isEmpty())
        assertEquals(HiddenListEmptiness.NothingHidden, state.emptiness)
        assertEquals(3, state.discoveredCount)
    }

    @Test
    fun `returning to all applications shows them again`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.camera")))
        model.onSectionChange(HiddenSection.Hidden)
        assertEquals(1, readyState(model).rows.size)

        model.onSectionChange(HiddenSection.All)

        assertEquals(3, readyState(model).rows.size)
    }

    // ------------------------------------------------------------------ hiding and unhiding

    @Test
    fun `hiding writes through the repository and shows what is stored`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.hide(camera)

        assertEquals(listOf("com.example.camera"), hidden.hideCalls.map { it.packageName })
        assertEquals(setOf("com.example.camera"), hidden.stored)
        assertEquals(
            ApplicationVisibility.Hidden,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.visibility,
        )
        assertEquals(R.string.apphide_manage_notice_changed, readyState(model).noticeRes)
        assertNull(readyState(model).failure)
    }

    @Test
    fun `unhiding writes through the repository and shows what is stored`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.unhide(camera)

        assertEquals(listOf("com.example.camera"), hidden.unhideCalls.map { it.packageName })
        assertTrue(hidden.stored.isEmpty())
        assertEquals(
            ApplicationVisibility.Visible,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.visibility,
        )
    }

    @Test
    fun `hiding twice stores one application, not two`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.hide(camera)
        model.hide(camera)

        assertEquals(2, hidden.hideCalls.size)
        assertEquals("a repeated hide is idempotent", setOf("com.example.camera"), hidden.stored)
    }

    @Test
    fun `unhiding twice is idempotent too`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.unhide(camera)
        model.unhide(camera)

        assertEquals(2, hidden.unhideCalls.size)
        assertTrue(hidden.stored.isEmpty())
        assertNull(readyState(model).failure)
    }

    @Test
    fun `the state comes back from the repository, never from the tap`() = runTest {
        // The repository accepts the write and then still reports nothing hidden: the screen must
        // show that, rather than the change it asked for.
        val hidden = FakeHiddenApplicationRepository().apply { writesAreIgnored = true }
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.hide(camera)

        assertEquals(
            ApplicationVisibility.Visible,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.visibility,
        )
    }

    @Test
    fun `a write the repository refuses is reported and changes nothing`() = runTest {
        val hidden = FakeHiddenApplicationRepository().apply {
            hideResult = NivaraResult.Failure(HiddenApplicationFailure.HiddenApplicationsUnwritable)
        }
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.hide(camera)

        val state = readyState(model)
        assertEquals(R.string.apphide_manage_error_change_not_stored, failureRes(state))
        assertNull(state.noticeRes)
        assertTrue("nothing was half-written", hidden.stored.isEmpty())
        assertEquals(
            ApplicationVisibility.Visible,
            state.rows.single { it.packageName == "com.example.camera" }.visibility,
        )
    }

    @Test
    fun `a change for an application that is gone writes nothing and refreshes the list`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val model = viewModel(repository = repository, hidden = hidden, session = authenticatedSession())
        val readsBefore = repository.calls

        model.hide(InstalledApplication("com.example.gone", "Gone"))

        val state = readyState(model)
        assertEquals(0, hidden.hideCalls.size)
        assertEquals(R.string.apphide_manage_error_not_installed, failureRes(state))
        assertTrue("the stale row is re-read away", repository.calls > readsBefore)
    }

    @Test
    fun `an application that is merely undiscovered keeps its stored entry`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.gone"))
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.unhide(InstalledApplication("com.example.gone", "Gone"))

        assertEquals("a discovery gap must not delete someone's hidden entry", 0, hidden.unhideCalls.size)
        assertEquals(setOf("com.example.gone"), hidden.stored)
    }

    @Test
    fun `a second change is refused while the first is being stored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val hidden = FakeHiddenApplicationRepository().apply { writeGate = gate }
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.hide(camera)
        assertTrue(readyState(model).busy)

        model.hide(notes)
        model.unhide(camera)

        assertEquals(
            "one change at a time",
            listOf("com.example.camera"),
            hidden.hideCalls.map { it.packageName },
        )
        assertEquals(0, hidden.unhideCalls.size)

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
        val hidden = FakeHiddenApplicationRepository()
        val model = viewModel(hidden = hidden, session = testSessionManager(clock))

        model.hide(camera)

        assertEquals(0, hidden.hideCalls.size)
        assertEquals(R.string.apphide_manage_error_locked, failureRes(readyState(model)))
    }

    @Test
    fun `a change is allowed once the existing gate has opened`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(hidden = hidden, session = session)
        model.hide(camera)
        assertEquals(0, hidden.hideCalls.size)

        session.establish(AuthenticationOutcome.Succeeded)
        model.hide(camera)

        assertEquals(1, hidden.hideCalls.size)
        assertTrue(readyState(model).sessionAuthenticated)
    }

    @Test
    fun `a failed authentication does not open the gate`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(hidden = hidden, session = session)

        session.establish(AuthenticationOutcome.Failed(blockedForMillis = 0L))
        model.hide(camera)

        assertEquals(0, hidden.hideCalls.size)
        assertEquals(R.string.apphide_manage_error_locked, failureRes(readyState(model)))
    }

    @Test
    fun `a change is refused once the session has expired`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(hidden = hidden, session = session)
        session.establish(AuthenticationOutcome.Succeeded)

        nowMillis += TEST_TIMEOUT_MILLIS
        model.hide(camera)

        assertEquals("an expired session authorizes nothing", 0, hidden.hideCalls.size)
        assertFalse(readyState(model).sessionAuthenticated)
    }

    @Test
    fun `quick lock closes the gate for changes`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val session = testSessionManager(clock)
        val model = viewModel(hidden = hidden, session = session)
        session.establish(AuthenticationOutcome.Succeeded)

        session.lockNow()
        model.hide(camera)

        assertEquals(0, hidden.hideCalls.size)
        assertEquals(R.string.apphide_manage_error_locked, failureRes(readyState(model)))
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
    fun `the screen never opens, extends or ends a session itself`() = runTest {
        val session = RecordingSessionManager(authenticatedSession())
        val hidden = FakeHiddenApplicationRepository()
        val model = viewModel(hidden = hidden, session = session)

        model.hide(camera)

        assertEquals("hiding establishes no session", 0, session.establishCalls)
        assertEquals("and it does not end one either", 0, session.lockNowCalls)
        assertTrue("it asks the gate, and only the gate", session.currentStateCalls > 0)
        assertEquals(1, hidden.hideCalls.size)
    }

    // ------------------------------------------------------------------ refreshing

    @Test
    fun `resuming re-reads the device and the stored set`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.camera"))
        val model = viewModel(repository = repository, hidden = hidden)
        val readsBefore = repository.calls
        val hiddenReadsBefore = hidden.readCalls

        model.onResumed()

        assertEquals(readsBefore + 1, repository.calls)
        assertEquals(hiddenReadsBefore + 1, hidden.readCalls)
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
        val hidden = FakeHiddenApplicationRepository()
        val model = viewModel(hidden = hidden)
        assertEquals(
            ApplicationVisibility.Visible,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.visibility,
        )

        hidden.stored = setOf("com.example.camera")
        model.onResumed()

        assertEquals(
            "the stored set stays the only source of truth",
            ApplicationVisibility.Hidden,
            readyState(model).rows.single { it.packageName == "com.example.camera" }.visibility,
        )
    }

    @Test
    fun `a resume keeps the query, the section and the order the user chose`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.camera")))
        model.onQueryChange("a")
        model.onSectionChange(HiddenSection.Hidden)
        model.onSortChange(ApplicationSortOrder.NameDescending)

        model.onResumed()

        val state = readyState(model)
        assertEquals("a", state.query)
        assertEquals(HiddenSection.Hidden, state.section)
        assertEquals(ApplicationSortOrder.NameDescending, state.sort)
    }

    @Test
    fun `retrying from the error state loads again`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Failure())
        val model = viewModel(repository = repository)
        assertEquals(HiddenManagementUiState.Error, model.uiState.value)

        repository.result = NivaraResult.Success(catalogue)
        model.refresh()

        assertEquals(3, readyState(model).rows.size)
    }

    // ------------------------------------------------------------------ helpers

    private fun authenticatedSession(): SessionManager =
        testSessionManager(clock).also { session -> session.establish(AuthenticationOutcome.Succeeded) }

    private fun viewModel(
        repository: ApplicationRepository = FakeApplicationRepository(NivaraResult.Success(catalogue)),
        hidden: FakeHiddenApplicationRepository = FakeHiddenApplicationRepository(),
        session: SessionManager = authenticatedSession(),
    ): HiddenManagementViewModel = HiddenManagementViewModel(
        applicationRepository = repository,
        hiddenApplicationRepository = hidden,
        sessionManager = session,
    )

    private fun readyState(viewModel: HiddenManagementViewModel): HiddenManagementUiState.Ready {
        val state = viewModel.uiState.value
        require(state is HiddenManagementUiState.Ready) { "the screen should be ready but was $state" }
        return state
    }

    /** The resource of the failure a ready state is showing, or `null` when it shows none. */
    private fun failureRes(state: HiddenManagementUiState.Ready): Int? = state.failure?.textRes

    /** How the fake hidden repository answers a read. */
    private enum class ReadMode { Available, Unreadable, Unavailable }

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

    private class FakeHiddenApplicationRepository(
        initial: Set<String> = emptySet(),
        var readMode: ReadMode = ReadMode.Available,
    ) : HiddenApplicationRepository {

        var stored: Set<String> = initial
        var readCalls = 0
        var hideCalls = mutableListOf<HiddenApplication>()
        var unhideCalls = mutableListOf<HiddenApplication>()
        var hideResult: NivaraResult<Unit> = NivaraResult.Success(Unit)
        var unhideResult: NivaraResult<Unit> = NivaraResult.Success(Unit)

        /** When true the write is accepted but the stored set does not change, as a broken store would. */
        var writesAreIgnored = false

        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun hiddenApplications(): HiddenApplicationsRead {
            readCalls++
            return when (readMode) {
                ReadMode.Unreadable -> HiddenApplicationsRead.Unreadable
                ReadMode.Unavailable -> HiddenApplicationsRead.Unavailable
                ReadMode.Available -> HiddenApplicationsRead.Available(
                    stored.mapTo(mutableSetOf()) { name -> HiddenApplication(name) },
                )
            }
        }

        override suspend fun hide(application: HiddenApplication): NivaraResult<Unit> {
            hideCalls += application
            writeGate?.await()
            if (hideResult is NivaraResult.Success && !writesAreIgnored) {
                stored = stored + application.packageName
            }
            return hideResult
        }

        override suspend fun unhide(application: HiddenApplication): NivaraResult<Unit> {
            unhideCalls += application
            writeGate?.await()
            if (unhideResult is NivaraResult.Success && !writesAreIgnored) {
                stored = stored - application.packageName
            }
            return unhideResult
        }
    }

    /**
     * The production session manager with a note of what was asked of it.
     *
     * Used for the one property a state assertion cannot show: that the screen never establishes,
     * extends or ends a session — it only asks the gate whether it is open.
     */
    private class RecordingSessionManager(private val delegate: SessionManager) : SessionManager {

        var establishCalls = 0
        var lockNowCalls = 0
        var currentStateCalls = 0

        override val state: StateFlow<SessionState> get() = delegate.state

        override fun establish(outcome: AuthenticationOutcome): SessionState {
            establishCalls++
            return delegate.establish(outcome)
        }

        override fun establish(outcome: BiometricAuthenticationOutcome): SessionState {
            establishCalls++
            return delegate.establish(outcome)
        }

        override fun currentState(): SessionState {
            currentStateCalls++
            return delegate.currentState()
        }

        override fun lockNow(): SessionState {
            lockNowCalls++
            return delegate.lockNow()
        }
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
