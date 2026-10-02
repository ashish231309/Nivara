package com.nivara.app.ui.camouflage

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.camouflage.CamouflageProfile
import com.nivara.app.domain.camouflage.CamouflageRepository
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
import com.nivara.app.testing.testSessionManager
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
 * Local JVM tests for the application-identity screen's state machine.
 *
 * Everything runs against a fake of the repository and the production session manager: nothing here
 * touches the platform, authenticates anybody or changes a component. What is verified is the logic
 * a device cannot be asked about cheaply — which identity is drawn, what a change requires, what
 * happens when the platform refuses one, what a closed gate does, and that a change is never
 * reported before it was read back.
 *
 * The session manager is the production one, driven through its own API, so the policy under test is
 * the policy that ships. The repository fake counts its calls, which is how "one change is one
 * change" and "the gate is asked rather than replaced" are asserted rather than promised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CamouflageViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

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

    // ------------------------------------------------------------------ reading

    @Test
    fun `the screen draws the identity the device is presenting`() = runTest {
        val model = viewModel(repository = FakeCamouflageRepository(CamouflageProfile.Calculator))

        assertEquals(CamouflageProfile.Calculator, readyState(model).selected)
    }

    @Test
    fun `nivara's own identity is drawn like any other`() = runTest {
        val model = viewModel(repository = FakeCamouflageRepository(CamouflageProfile.Nivara))

        val state = readyState(model)
        assertEquals(CamouflageProfile.Nivara, state.selected)
        assertFalse(state.selected.isCamouflage)
    }

    @Test
    fun `the repository is read, not a remembered selection`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Weather)
        val model = viewModel(repository = repository)

        model.onResumed()

        assertEquals("each read asks the device", 2, repository.readCalls)
        assertEquals(CamouflageProfile.Weather, readyState(model).selected)
    }

    @Test
    fun `a change made outside nivara is shown when the screen comes back`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Nivara)
        val model = viewModel(repository = repository)
        assertEquals(CamouflageProfile.Nivara, readyState(model).selected)

        // Changed by Android, a restore, or a tool that reset the application's components.
        repository.profile = CamouflageProfile.Notes
        model.onResumed()

        assertEquals(
            "the device stays the source of truth",
            CamouflageProfile.Notes,
            readyState(model).selected,
        )
    }

    @Test
    fun `a repository that throws is still an answer, not a crash`() = runTest {
        val model = viewModel(repository = FakeCamouflageRepository(CamouflageProfile.Notes, readThrows = true))

        assertEquals(
            "the default identity is drawn rather than no screen at all",
            CamouflageProfile.Nivara,
            readyState(model).selected,
        )
    }

    // ------------------------------------------------------------------ changing

    @Test
    fun `a change with a valid session is applied and read back`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Nivara)
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.select(CamouflageProfile.Calculator)

        assertEquals(listOf(CamouflageProfile.Calculator), repository.writes)
        assertEquals(CamouflageProfile.Calculator, readyState(model).selected)
        assertEquals(R.string.camouflage_notice_changed, readyState(model).noticeRes)
        assertNull(readyState(model).failure)
    }

    @Test
    fun `a change without a session is refused and nothing is written`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Nivara)
        val model = viewModel(repository = repository, session = testSessionManager(clock))

        model.select(CamouflageProfile.Notes)

        assertTrue("the screen must ask for the existing credential flow", readyState(model).unlockRequired)
        assertEquals(R.string.camouflage_locked, readyState(model).failure?.textRes)
        assertEquals("nothing may be changed without a session", emptyList<CamouflageProfile>(), repository.writes)
        assertEquals(CamouflageProfile.Nivara, readyState(model).selected)
    }

    @Test
    fun `an expired session cannot change the identity`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Nivara)
        val session = testSessionManager(clock)
        val model = viewModel(repository = repository, session = session)
        session.establish(AuthenticationOutcome.Succeeded)

        nowMillis += TEST_SESSION_TIMEOUT_MILLIS
        model.select(CamouflageProfile.Weather)

        assertTrue(readyState(model).unlockRequired)
        assertEquals(emptyList<CamouflageProfile>(), repository.writes)
    }

    @Test
    fun `quick lock puts the screen back behind the gate`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Nivara)
        val session = testSessionManager(clock)
        val model = viewModel(repository = repository, session = session)
        session.establish(AuthenticationOutcome.Succeeded)
        assertTrue(readyState(model).sessionAuthenticated)

        session.lockNow()

        val state = readyState(model)
        assertFalse(state.sessionAuthenticated)
        assertFalse(state.canChange)

        model.select(CamouflageProfile.Notes)

        assertEquals("a locked screen changes nothing", emptyList<CamouflageProfile>(), repository.writes)
        assertTrue(readyState(model).unlockRequired)
    }

    @Test
    fun `a refused change is reported and the identity shown is the one in use`() = runTest {
        val repository = FakeCamouflageRepository(
            CamouflageProfile.Nivara,
            selectResult = NivaraResult.Failure(),
        )
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.select(CamouflageProfile.Weather)

        val state = readyState(model)
        assertEquals(R.string.camouflage_error_not_changed, state.failure?.textRes)
        assertNull("a refused change is not a confirmation", state.noticeRes)
        assertEquals(
            "the screen shows what the device reports, not what the tap asked for",
            CamouflageProfile.Nivara,
            state.selected,
        )
    }

    @Test
    fun `a repository that throws on a change is a failure, not a crash`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Nivara, selectThrows = true)
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.select(CamouflageProfile.Notes)

        assertEquals(R.string.camouflage_error_not_changed, readyState(model).failure?.textRes)
        assertEquals(CamouflageProfile.Nivara, readyState(model).selected)
    }

    @Test
    fun `the identity that is already presented is not changed again`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Calculator)
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.select(CamouflageProfile.Calculator)

        assertEquals("there is no change to make", emptyList<CamouflageProfile>(), repository.writes)
        assertNull(readyState(model).noticeRes)
    }

    @Test
    fun `restoring nivara is an ordinary selection`() = runTest {
        val repository = FakeCamouflageRepository(CamouflageProfile.Weather)
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.select(CamouflageProfile.Nivara)

        assertEquals(listOf(CamouflageProfile.Nivara), repository.writes)
        assertEquals(CamouflageProfile.Nivara, readyState(model).selected)
        assertEquals("every identity is one of the choices", 4, CamouflageProfile.entries.size)
    }

    @Test
    fun `a second change is refused while the first is being applied`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeCamouflageRepository(CamouflageProfile.Nivara).apply { selectGate = gate }
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.select(CamouflageProfile.Notes)
        assertTrue("a change in flight is visible to the screen", readyState(model).busy)

        model.select(CamouflageProfile.Calculator)

        assertEquals("one change at a time", listOf(CamouflageProfile.Notes), repository.writes)

        // Let the held change finish so nothing is left suspended when the test ends.
        gate.complete(Unit)
    }

    @Test
    fun `a message can be cleared without changing the identity`() = runTest {
        val repository = FakeCamouflageRepository(
            CamouflageProfile.Nivara,
            selectResult = NivaraResult.Failure(),
        )
        val model = viewModel(repository = repository, session = authenticatedSession())
        model.select(CamouflageProfile.Notes)

        model.onMessageShown()

        assertNull(readyState(model).failure)
        assertEquals(CamouflageProfile.Nivara, readyState(model).selected)
    }

    @Test
    fun `asking for a change hands the user to the credential screen exactly once`() = runTest {
        val model = viewModel(session = testSessionManager(clock))
        model.select(CamouflageProfile.Notes)
        assertTrue(readyState(model).unlockRequired)

        model.onUnlockHandled()

        assertFalse("the request is answered once", readyState(model).unlockRequired)
    }

    // ------------------------------------------------------------------ the session

    @Test
    fun `the screen never opens, extends or ends a session`() = runTest {
        val session = RecordingSessionManager(authenticatedSession())
        val model = viewModel(repository = FakeCamouflageRepository(CamouflageProfile.Nivara), session = session)

        model.select(CamouflageProfile.Calculator)
        model.onResumed()
        model.select(CamouflageProfile.Nivara)

        assertEquals("the gate is asked, never written", 0, session.establishCalls)
        assertEquals(0, session.lockNowCalls)
        assertTrue("the gate's authoritative read is what is used", session.currentStateCalls > 0)
    }

    @Test
    fun `a resumed screen does not re-authenticate`() = runTest {
        val session = authenticatedSession()
        val model = viewModel(repository = FakeCamouflageRepository(CamouflageProfile.Notes), session = session)

        model.onResumed()
        model.onResumed()

        assertTrue("the session it already had is the one it uses", readyState(model).sessionAuthenticated)
    }

    @Test
    fun `a new screen after a restart starts with no session`() = runTest {
        // The identity survives the process because the platform holds it; the authorization does
        // not, because the session is memory-only. A restarted screen therefore shows the identity
        // and still asks for authentication before changing it.
        val repository = FakeCamouflageRepository(CamouflageProfile.Weather)
        val model = viewModel(repository = repository)

        val state = readyState(model)
        assertEquals("the identity is what the device reports", CamouflageProfile.Weather, state.selected)
        assertFalse("nothing authenticated is reconstructed", state.sessionAuthenticated)
        assertFalse(state.canChange)
    }

    @Test
    fun `an authenticated session needs no second authentication system`() = runTest {
        // Biometric and credential paths both end at the same gate; this screen only reads it. A
        // session opened by either mechanism is enough, and the screen asks for nothing else.
        val session = testSessionManager(clock)
        val model = viewModel(repository = FakeCamouflageRepository(CamouflageProfile.Nivara), session = session)
        session.establish(BiometricAuthenticationOutcome.Succeeded)

        assertTrue(readyState(model).sessionAuthenticated)
        model.select(CamouflageProfile.Notes)
        assertEquals(CamouflageProfile.Notes, readyState(model).selected)
    }

    // ------------------------------------------------------------------ helpers

    private fun authenticatedSession(): SessionManager =
        testSessionManager(clock).also { session -> session.establish(AuthenticationOutcome.Succeeded) }

    private fun viewModel(
        repository: FakeCamouflageRepository = FakeCamouflageRepository(CamouflageProfile.Nivara),
        session: SessionManager = testSessionManager(clock),
    ): CamouflageViewModel = CamouflageViewModel(
        camouflageRepository = repository,
        sessionManager = session,
    )

    private fun readyState(viewModel: CamouflageViewModel): CamouflageUiState.Ready {
        val state = viewModel.uiState.value
        require(state is CamouflageUiState.Ready) { "the screen should be ready but was $state" }
        return state
    }

    /** A stand-in platform: it remembers one identity and counts what it was asked to do. */
    private class FakeCamouflageRepository(
        var profile: CamouflageProfile,
        var readThrows: Boolean = false,
        var selectResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
        var selectThrows: Boolean = false,
    ) : CamouflageRepository {

        val writes = mutableListOf<CamouflageProfile>()
        var readCalls = 0
        var selectGate: CompletableDeferred<Unit>? = null

        override suspend fun currentProfile(): CamouflageProfile {
            readCalls++
            if (readThrows) throw IllegalStateException("the platform could not be read")
            return profile
        }

        override suspend fun selectProfile(profile: CamouflageProfile): NivaraResult<Unit> {
            writes += profile
            selectGate?.await()
            if (selectThrows) throw IllegalStateException("the platform refused the change")
            if (selectResult is NivaraResult.Success) {
                this.profile = profile
            }
            return selectResult
        }
    }

    /**
     * The production session manager with a note of what was asked of it.
     *
     * Used for the one property a state assertion cannot show: that the identity screen never opens,
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

}
