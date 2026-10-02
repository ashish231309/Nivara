package com.nivara.app.ui.launcher

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.app.ApplicationLauncher
import com.nivara.app.domain.app.ApplicationLaunchFailure
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationRepository
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
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
 * Local JVM tests for the launcher's state machine.
 *
 * Everything runs against fakes of the interfaces: nothing here reads a file, discovers an
 * application, authenticates anybody or starts anything. What is verified is the logic a device
 * cannot be asked about cheaply — which applications are drawn, what happens when the stored hidden
 * set cannot be read, when a reveal is allowed, what ends it, and what a launch does and does not
 * change.
 *
 * The session manager is the production one, driven through its own API, so the policy under test is
 * the policy that ships. The hidden and protected sets are fakes that count their calls, which is
 * how "the launcher never writes hidden state" and "the launcher cannot reach the protected set" are
 * asserted rather than promised.
 *
 * Whether Android starts the launcher, resolves a launch intent, or keeps Nivara as Home is only
 * knowable on a device. None of that is claimed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    /** The test's clock. Moving it forward is how a session expires. */
    private var nowMillis: Long = 1_000L
    private val clock = TimeProvider { nowMillis }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ what is drawn

    @Test
    fun `a hidden application is left out of the drawer`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")))

        val state = readyState(model)

        assertEquals(listOf("com.example.camera", "com.example.maps"), state.entries.map { it.packageName })
        assertEquals(3, state.discoveredCount)
        assertEquals(1, state.hiddenCount)
        assertEquals(1, state.withheldCount)
        assertFalse(state.revealed)
    }

    @Test
    fun `nothing hidden draws everything`() = runTest {
        val state = readyState(viewModel())

        assertEquals(3, state.entries.size)
        assertEquals(0, state.hiddenCount)
        assertEquals(0, state.withheldCount)
        assertNull(state.emptiness)
    }

    @Test
    fun `every application hidden is its own empty state, not an empty device`() = runTest {
        val model = viewModel(
            hidden = FakeHiddenApplicationRepository(
                setOf("com.example.camera", "com.example.notes", "com.example.maps"),
            ),
        )

        val state = readyState(model)

        assertTrue(state.entries.isEmpty())
        assertEquals(LauncherListEmptiness.AllApplicationsHidden, state.emptiness)
        assertEquals(3, state.withheldCount)
    }

    @Test
    fun `a device with no launchable applications says so`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Success(emptyList())))

        val state = readyState(model)

        assertTrue(state.entries.isEmpty())
        assertEquals(LauncherListEmptiness.DeviceHasNoApplications, state.emptiness)
    }

    @Test
    fun `a search that matches nothing is not confused with any other empty state`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")))

        model.onQueryChange("zzz")

        val state = readyState(model)
        assertTrue(state.entries.isEmpty())
        assertEquals(LauncherListEmptiness.NoSearchResults, state.emptiness)
        assertEquals(3, state.discoveredCount)
    }

    @Test
    fun `searching matches a label or a package and writes nothing`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden)

        model.onQueryChange("map")
        assertEquals(listOf("com.example.maps"), readyState(model).entries.map { it.packageName })

        model.onQueryChange("notes")
        assertTrue(
            "a hidden application must not become findable through search",
            readyState(model).entries.isEmpty(),
        )

        model.onQueryChange("")
        assertEquals(2, readyState(model).entries.size)
        assertEquals(0, hidden.writeCalls)
    }

    @Test
    fun `both orderings come from the domain comparator`() = runTest {
        val model = viewModel()

        assertEquals(
            listOf("Camera", "Maps", "Notes"),
            readyState(model).entries.map { it.label },
        )

        model.onSortChange(ApplicationSortOrder.NameDescending)

        val state = readyState(model)
        assertEquals(listOf("Notes", "Maps", "Camera"), state.entries.map { it.label })
        assertEquals(ApplicationSortOrder.NameDescending, state.sort)
    }

    @Test
    fun `an empty drawer is still a ready launcher`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Success(emptyList())))

        assertEquals(LauncherListEmptiness.DeviceHasNoApplications, readyState(model).emptiness)
        assertTrue(readyState(model).entries.isEmpty())
    }

    @Test
    fun `the first answer arrives as loading, and the launcher is ready afterwards`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue)).apply { readGate = gate }

        val model = viewModel(repository = repository)

        assertEquals(LauncherUiState.Loading, model.uiState.value)

        gate.complete(Unit)
        assertTrue(model.uiState.value is LauncherUiState.Ready)
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    fun `an unreadable hidden set draws no application at all`() = runTest {
        val model = viewModel(
            repository = FakeApplicationRepository(NivaraResult.Success(catalogue)),
            hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unreadable),
        )

        val state = model.uiState.value
        assertEquals(LauncherUiState.HiddenStateUnreadable, state)
        assertFalse("there must be no list to draw", state is LauncherUiState.Ready)
    }

    @Test
    fun `an unavailable hidden set draws no application either, and is its own state`() = runTest {
        val model = viewModel(
            hidden = FakeHiddenApplicationRepository(readMode = ReadMode.Unavailable),
        )

        assertEquals(LauncherUiState.HiddenStateUnavailable, model.uiState.value)
    }

    @Test
    fun `discovery failing is not an empty device`() = runTest {
        val model = viewModel(repository = FakeApplicationRepository(NivaraResult.Failure()))

        val state = model.uiState.value
        assertEquals(LauncherUiState.DiscoveryUnavailable, state)
        assertFalse("no list may be drawn from a failed discovery", state is LauncherUiState.Ready)
    }

    @Test
    fun `a hidden set that becomes unreadable takes the drawn list away`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden)
        assertEquals(2, readyState(model).entries.size)

        hidden.readMode = ReadMode.Unreadable
        model.onResumed()

        assertEquals(
            "a drawer that cannot be filtered is not drawn at all",
            LauncherUiState.HiddenStateUnreadable,
            model.uiState.value,
        )
    }

    @Test
    fun `a discovery failure after a successful read keeps the list and says what happened`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val model = viewModel(repository = repository)
        assertEquals(3, readyState(model).entries.size)

        repository.result = NivaraResult.Failure()
        model.refresh()

        val state = readyState(model)
        assertEquals("a failed refresh is not evidence that the device changed", 3, state.entries.size)
        assertEquals(R.string.launcher_error_discovery, state.failure?.textRes)
    }

    @Test
    fun `a repository that throws becomes a state instead of a crash`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue)).apply {
            thrown = IllegalStateException("the platform failed")
        }

        assertEquals(LauncherUiState.DiscoveryUnavailable, viewModel(repository = repository).uiState.value)
    }

    @Test
    fun `an unreadable hidden set also refuses a reveal that was active`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden, session = authenticatedSession())
        model.revealHiddenApplications()
        assertTrue(readyState(model).revealed)

        hidden.readMode = ReadMode.Unavailable
        model.onResumed()

        assertEquals(LauncherUiState.HiddenStateUnavailable, model.uiState.value)
    }

    // ------------------------------------------------------------------ the reveal

    @Test
    fun `without a session a reveal is refused and hidden applications stay hidden`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden, session = testSessionManager(clock))

        model.revealHiddenApplications()

        val state = readyState(model)
        assertFalse(state.revealed)
        assertTrue("the screen is told to ask for the credential screen", state.unlockRequired)
        assertEquals(2, state.entries.size)
        assertTrue("nothing was written", hidden.writeCalls == 0)
    }

    @Test
    fun `with a valid session a reveal shows the hidden applications`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.revealHiddenApplications()

        val state = readyState(model)
        assertTrue(state.revealed)
        assertFalse(state.unlockRequired)
        assertEquals(3, state.entries.size)
        assertEquals(0, state.withheldCount)
        assertEquals("the stored set is untouched", 1, state.hiddenCount)
        assertEquals(0, hidden.writeCalls)
    }

    @Test
    fun `a reveal needs something to reveal`() = runTest {
        val model = viewModel(session = authenticatedSession())

        model.revealHiddenApplications()

        val state = readyState(model)
        assertFalse(state.revealed)
        assertFalse("there is nothing to unlock for", state.unlockRequired)
        assertFalse(state.canReveal)
    }

    @Test
    fun `a request answered by the credential screen is cleared afterwards`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")))
        model.revealHiddenApplications()
        assertTrue(readyState(model).unlockRequired)

        model.onUnlockHandled()

        assertFalse(readyState(model).unlockRequired)
    }

    @Test
    fun `a reveal can be ended by the user without touching the stored set`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden, session = authenticatedSession())
        model.revealHiddenApplications()

        model.concealHiddenApplications()

        val state = readyState(model)
        assertFalse(state.revealed)
        assertEquals(2, state.entries.size)
        assertEquals(1, state.withheldCount)
        assertEquals(0, hidden.writeCalls)
    }

    @Test
    fun `the hidden section exists only while a reveal does`() = runTest {
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")))
        model.onSectionChange(LauncherSection.Hidden)
        assertEquals(
            "with nothing revealed the hidden section is not a place with content",
            LauncherSection.All,
            readyState(model).section,
        )

        val session = authenticatedSession()
        val opened = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = session)
        opened.revealHiddenApplications()
        opened.onSectionChange(LauncherSection.Hidden)

        val state = readyState(opened)
        assertEquals(LauncherSection.Hidden, state.section)
        assertEquals(
            "the tail of the drawer is the one stored application, matched by package name",
            listOf(notes.packageName),
            state.entries.map { it.packageName },
        )

        opened.concealHiddenApplications()

        assertEquals(
            "ending the reveal ends the section with it",
            LauncherSection.All,
            readyState(opened).section,
        )
    }

    @Test
    fun `a hidden section that empties says nothing is hidden rather than nothing was found`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf(notes.packageName))
        val model = viewModel(hidden = hidden, session = authenticatedSession())
        model.revealHiddenApplications()
        model.onSectionChange(LauncherSection.Hidden)
        assertEquals(
            listOf(notes.packageName),
            readyState(model).entries.map { it.packageName },
        )

        // The stored set changes elsewhere — the management screen unhides the last application —
        // and the launcher comes back to find it. The session is still valid, so the reveal and the
        // section survive, but the section is empty and the wording must say which nothing this is.
        hidden.stored = emptySet()
        model.onResumed()

        val state = readyState(model)
        assertTrue(state.entries.isEmpty())
        assertTrue("the reveal is still authorized", state.revealed)
        assertEquals(0, state.hiddenCount)
        assertEquals(LauncherListEmptiness.NothingHidden, state.emptiness)
    }

    // ------------------------------------------------------------------ the session

    @Test
    fun `an expired session cannot reveal hidden applications`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val session = testSessionManager(clock)
        val model = viewModel(hidden = hidden, session = session)
        session.establish(AuthenticationOutcome.Succeeded)

        nowMillis += TEST_SESSION_TIMEOUT_MILLIS
        model.revealHiddenApplications()

        val state = readyState(model)
        assertFalse(state.revealed)
        assertTrue(state.unlockRequired)
        assertEquals(2, state.entries.size)
        assertFalse(state.sessionAuthenticated)
    }

    @Test
    fun `an expired session ends a reveal that was already active`() = runTest {
        val session = testSessionManager(clock)
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = session)
        session.establish(AuthenticationOutcome.Succeeded)
        model.revealHiddenApplications()
        assertEquals(3, readyState(model).entries.size)

        nowMillis += TEST_SESSION_TIMEOUT_MILLIS
        model.onResumed()

        val state = readyState(model)
        assertFalse(state.revealed)
        assertEquals("hidden applications are withheld again", 2, state.entries.size)
        assertEquals(1, state.withheldCount)
    }

    @Test
    fun `resuming while the session is still valid keeps the reveal`() = runTest {
        val session = testSessionManager(clock)
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = session)
        session.establish(AuthenticationOutcome.Succeeded)
        model.revealHiddenApplications()

        model.onResumed()

        val state = readyState(model)
        assertTrue(
            "resuming does not re-authenticate, so it must not re-hide either",
            state.revealed,
        )
        assertEquals(3, state.entries.size)
        assertEquals(0, state.withheldCount)
    }

    @Test
    fun `quick lock ends a reveal immediately`() = runTest {
        val session = testSessionManager(clock)
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = session)
        session.establish(AuthenticationOutcome.Succeeded)
        model.revealHiddenApplications()
        assertTrue(readyState(model).revealed)

        session.lockNow()

        val state = readyState(model)
        assertFalse(state.revealed)
        assertEquals(2, state.entries.size)
        assertFalse(state.sessionAuthenticated)
    }

    @Test
    fun `the launcher notices the gate closing while it is on screen`() = runTest {
        val session = testSessionManager(clock)
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = session)
        session.establish(AuthenticationOutcome.Succeeded)
        model.revealHiddenApplications()

        session.lockNow()

        assertFalse("the drawer must not keep showing them", readyState(model).revealed)
    }

    @Test
    fun `the launcher never opens, extends or ends a session`() = runTest {
        val session = RecordingSessionManager(authenticatedSession())
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = session)
        val expiryBefore = session.currentState().let { state -> (state as SessionState.Authenticated).expiresAtMillis }

        model.revealHiddenApplications()
        model.concealHiddenApplications()
        model.onResumed()

        assertEquals("the launcher establishes no session", 0, session.establishCalls)
        assertEquals("and it ends none", 0, session.lockNowCalls)
        assertTrue("it asks the gate, and only the gate", session.currentStateCalls > 0)
        val expiryAfter = session.currentState().let { state -> (state as SessionState.Authenticated).expiresAtMillis }
        assertEquals("resuming the launcher must not extend the session", expiryBefore, expiryAfter)
    }

    @Test
    fun `a recreated launcher has nothing to restore and no reveal`() = runTest {
        // A new process: a new view model, a new session manager, and no state carried over from
        // the previous run. The reveal is memory-only, so there is nothing to bring back.
        val first = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = authenticatedSession())
        first.revealHiddenApplications()
        assertTrue(readyState(first).revealed)

        val recreated = viewModel(
            hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")),
            session = testSessionManager(clock),
        )

        assertFalse(readyState(recreated).revealed)
        assertEquals(2, readyState(recreated).entries.size)
    }

    @Test
    fun `a failed authentication does not reveal anything`() = runTest {
        val session = testSessionManager(clock)
        val model = viewModel(hidden = FakeHiddenApplicationRepository(setOf("com.example.notes")), session = session)

        session.establish(AuthenticationOutcome.Failed(blockedForMillis = 0L))
        model.revealHiddenApplications()

        val state = readyState(model)
        assertFalse(state.revealed)
        assertTrue(state.unlockRequired)
    }

    // ------------------------------------------------------------------ launching

    @Test
    fun `launching starts the exact package the row carries`() = runTest {
        val launcher = FakeApplicationLauncher()
        val model = viewModel(launcher = launcher)

        model.launch(maps)

        assertEquals(listOf("com.example.maps"), launcher.launched)
        assertNull(readyState(model).failure)
    }

    @Test
    fun `a hidden application can be launched once it is revealed, and still cannot be launched before`() = runTest {
        val launcher = FakeApplicationLauncher()
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val notRevealed = viewModel(launcher = launcher, hidden = hidden)
        assertTrue(
            "the row is not on screen, so nothing can ask for it",
            readyState(notRevealed).entries.none { it.packageName == notes.packageName },
        )

        val revealed = viewModel(launcher = launcher, hidden = hidden, session = authenticatedSession())
        revealed.revealHiddenApplications()
        revealed.launch(notes)

        assertEquals(listOf("com.example.notes"), launcher.launched)
    }

    @Test
    fun `a launch failure is reported and opens nothing`() = runTest {
        val launcher = FakeApplicationLauncher(
            result = NivaraResult.Failure(ApplicationLaunchFailure.LaunchRefused),
        )
        val model = viewModel(launcher = launcher)

        model.launch(camera)

        assertEquals(R.string.launcher_error_launch_failed, readyState(model).failure?.textRes)
    }

    @Test
    fun `an application that disappeared is not launched and the list is re-read`() = runTest {
        val launcher = FakeApplicationLauncher()
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val model = viewModel(repository = repository, launcher = launcher)
        val readsBefore = repository.calls

        model.launch(InstalledApplication("com.example.gone", "Gone"))

        val state = readyState(model)
        assertEquals(
            "nothing may be started from a row that is not in the catalogue",
            0,
            launcher.launched.size,
        )
        assertEquals(R.string.launcher_error_not_installed, state.failure?.textRes)
        assertTrue("the stale row is re-read away", repository.calls > readsBefore)
    }

    @Test
    fun `a second launch is refused while the first is being started`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val launcher = FakeApplicationLauncher().apply { launchGate = gate }
        val model = viewModel(launcher = launcher)

        model.launch(camera)
        assertTrue(readyState(model).busy)

        model.launch(notes)

        assertEquals("one launch at a time", listOf("com.example.camera"), launcher.launched)

        // Let the held launch finish so nothing is left suspended when the test ends.
        gate.complete(Unit)
    }

    @Test
    fun `a launcher that throws is a failed launch, not a crash`() = runTest {
        val launcher = FakeApplicationLauncher().apply { thrown = IllegalStateException("refused") }
        val model = viewModel(launcher = launcher)

        model.launch(camera)

        assertEquals(R.string.launcher_error_launch_failed, readyState(model).failure?.textRes)
    }

    @Test
    fun `a message can be cleared without changing the list`() = runTest {
        val model = viewModel(
            launcher = FakeApplicationLauncher(result = NivaraResult.Failure(ApplicationLaunchFailure.LaunchRefused)),
        )
        model.launch(camera)
        assertEquals(R.string.launcher_error_launch_failed, readyState(model).failure?.textRes)

        model.onMessageShown()

        assertNull(readyState(model).failure)
        assertEquals(3, readyState(model).entries.size)
    }

    // ------------------------------------------------------------------ independence and refresh

    @Test
    fun `the launcher never writes hidden state, whatever happens on screen`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden, session = authenticatedSession())

        model.revealHiddenApplications()
        model.onSectionChange(LauncherSection.Hidden)
        model.onQueryChange("note")
        model.onSortChange(ApplicationSortOrder.NameDescending)
        model.launch(notes)
        model.concealHiddenApplications()
        model.onResumed()

        assertEquals("the hidden set is read, never written", 0, hidden.writeCalls)
        assertEquals(setOf("com.example.notes"), hidden.stored)
    }

    @Test
    fun `reading the drawer needs no session and changes nothing`() = runTest {
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val model = viewModel(hidden = hidden, session = testSessionManager(clock))

        val state = readyState(model)

        assertFalse(state.sessionAuthenticated)
        assertEquals(2, state.entries.size)
        assertEquals(0, hidden.writeCalls)
        assertNull(state.failure)
    }

    @Test
    fun `resuming re-reads the device and the stored set without asking for authentication`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val hidden = FakeHiddenApplicationRepository(setOf("com.example.notes"))
        val session = testSessionManager(clock)
        val model = viewModel(repository = repository, hidden = hidden, session = session)
        val readsBefore = repository.calls
        val hiddenReadsBefore = hidden.readCalls

        model.onResumed()

        assertEquals(readsBefore + 1, repository.calls)
        assertEquals(hiddenReadsBefore + 1, hidden.readCalls)
        assertFalse(readyState(model).sessionAuthenticated)
    }

    @Test
    fun `resuming picks up an application that appeared while the launcher was away`() = runTest {
        val repository = FakeApplicationRepository(NivaraResult.Success(catalogue))
        val model = viewModel(repository = repository)
        assertEquals(3, readyState(model).entries.size)

        repository.result = NivaraResult.Success(catalogue + InstalledApplication("com.example.alarm", "Alarm"))
        model.onResumed()

        assertEquals(4, readyState(model).entries.size)
    }

    @Test
    fun `resuming picks up a change made in Nivara settings`() = runTest {
        val hidden = FakeHiddenApplicationRepository()
        val model = viewModel(hidden = hidden)
        assertEquals(3, readyState(model).entries.size)

        hidden.stored = setOf("com.example.notes")
        model.onResumed()

        assertEquals(
            "the repository stays the only source of truth",
            listOf("com.example.camera", "com.example.maps"),
            readyState(model).entries.map { it.packageName },
        )
    }

    @Test
    fun `resuming keeps the query and the ordering the user chose`() = runTest {
        val model = viewModel()
        model.onQueryChange("a")
        model.onSortChange(ApplicationSortOrder.NameDescending)

        model.onResumed()

        val state = readyState(model)
        assertEquals("a", state.query)
        assertEquals(ApplicationSortOrder.NameDescending, state.sort)
    }

    // ------------------------------------------------------------------ helpers

    private fun authenticatedSession(): SessionManager =
        testSessionManager(clock).also { session -> session.establish(AuthenticationOutcome.Succeeded) }

    private fun viewModel(
        repository: ApplicationRepository = FakeApplicationRepository(NivaraResult.Success(catalogue)),
        hidden: FakeHiddenApplicationRepository = FakeHiddenApplicationRepository(),
        launcher: ApplicationLauncher = FakeApplicationLauncher(),
        // Unauthenticated by default: reading the drawer needs no session, so a test that wants a
        // reveal has to say so, and a test that forgets cannot pass by accident.
        session: SessionManager = testSessionManager(clock),
    ): LauncherViewModel = LauncherViewModel(
        applicationRepository = repository,
        hiddenApplicationRepository = hidden,
        applicationLauncher = launcher,
        sessionManager = session,
    )

    private fun readyState(viewModel: LauncherViewModel): LauncherUiState.Ready {
        val state = viewModel.uiState.value
        require(state is LauncherUiState.Ready) { "the launcher should be ready but was $state" }
        return state
    }

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
        var writeCalls = 0

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
            writeCalls++
            stored = stored + application.packageName
            return NivaraResult.Success(Unit)
        }

        override suspend fun unhide(application: HiddenApplication): NivaraResult<Unit> {
            writeCalls++
            stored = stored - application.packageName
            return NivaraResult.Success(Unit)
        }
    }

    private class FakeApplicationLauncher(
        var result: NivaraResult<Unit> = NivaraResult.Success(Unit),
    ) : ApplicationLauncher {

        val launched = mutableListOf<String>()
        var launchGate: CompletableDeferred<Unit>? = null
        var thrown: Exception? = null

        override suspend fun launch(packageName: String): NivaraResult<Unit> {
            launched += packageName
            launchGate?.await()
            thrown?.let { error -> throw error }
            return result
        }
    }

    /**
     * The production session manager with a note of what was asked of it.
     *
     * Used for the one property a state assertion cannot show: that the launcher never establishes,
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
        val camera = InstalledApplication("com.example.camera", "Camera")
        val notes = InstalledApplication("com.example.notes", "Notes")
        val maps = InstalledApplication("com.example.maps", "Maps")

        /** The three applications in the domain's order: Camera, Maps, Notes. */
        val catalogue = listOf(camera, maps, notes)
    }
}
