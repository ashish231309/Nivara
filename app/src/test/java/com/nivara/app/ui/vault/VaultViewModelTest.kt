package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.FakeVaultLocationStore
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationRead
import com.nivara.app.domain.vault.VaultOrganizationRepository
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadableReason
import com.nivara.app.testing.FakeVaultOrganizationRepository
import com.nivara.app.testing.FakeVaultTrashRepository
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
import com.nivara.app.testing.testSessionManager
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local JVM tests for the vault screen's state machine.
 *
 * Everything here runs against fakes of the repository and the location store, plus the production
 * session manager driven through its own API: no platform, no storage, no authentication. What is
 * verified is the logic a device cannot be asked about cheaply — which state is drawn, which control
 * is offered for it, what a change requires, and the one property that matters most: the screen never
 * decides that a vault it could not read is a vault that is not there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private var nowMillis: Long = 1_000L
    private val clock = TimeProvider { nowMillis }
    private val random = SecureRandomGenerator()

    private val rootReference = "content://com.android.externalstorage.documents/tree/primary%3ANivara"

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
    fun `a device with no chosen folder says so and offers to choose one`() = runTest {
        val model = viewModel(repository = FakeVaultRepository(VaultState.NotConfigured))

        val state = readyState(model)
        assertEquals(VaultState.NotConfigured, state.vault)
        assertFalse("nothing is created without a folder", state.canInitialize)
        assertFalse(state.canReplaceUnreadable)
    }

    @Test
    fun `a vault that can be opened is drawn as ready without a session`() = runTest {
        val identity = VaultIdentity.create(random)
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.Ready(identity, formatVersion = 1)),
            session = testSessionManager(clock),
        )

        val state = readyState(model)
        assertEquals(VaultState.Ready(identity, formatVersion = 1), state.vault)
        assertFalse("looking at the vault is not an authorized act", state.sessionAuthenticated)
        assertFalse("and nothing destructive is offered", state.canReplaceUnreadable)
        assertFalse(state.canInitialize)
    }

    @Test
    fun `a folder without a vault offers to create one once the gate is open`() = runTest {
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.Missing),
            session = authenticatedSession(),
        )

        val state = readyState(model)
        assertTrue(state.canInitialize)
        assertFalse("there is nothing to replace", state.canReplaceUnreadable)
    }

    @Test
    fun `an unreadable vault offers replacement and never creation`() = runTest {
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged)),
            session = authenticatedSession(),
        )

        val state = readyState(model)
        assertTrue(state.canReplaceUnreadable)
        assertFalse("creating a vault over an unreadable one is not the ordinary path", state.canInitialize)
    }

    @Test
    fun `an unfinished setup is completed rather than replaced`() = runTest {
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete)),
            session = authenticatedSession(),
        )

        val state = readyState(model)
        assertTrue(state.canInitialize)
        assertFalse("there is nothing to give up", state.canReplaceUnreadable)
    }

    @Test
    fun `a vault from a newer version is neither created over nor replaced`() = runTest {
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.UnsupportedVersion(fileVersion = 4)),
            session = authenticatedSession(),
        )

        val state = readyState(model)
        assertFalse("a later Nivara can still open it", state.canInitialize)
        assertFalse("so nothing here may destroy it", state.canReplaceUnreadable)
    }

    @Test
    fun `an unreachable folder is drawn as unreachable, not as an empty one`() = runTest {
        val model = viewModel(repository = FakeVaultRepository(VaultState.Unavailable))

        val state = readyState(model)
        assertEquals(VaultState.Unavailable, state.vault)
        assertFalse(state.canInitialize)
    }

    @Test
    fun `the storage is read again when the screen comes back`() = runTest {
        val repository = FakeVaultRepository(VaultState.NotConfigured)
        val model = viewModel(repository = repository, session = authenticatedSession())
        assertEquals(VaultState.NotConfigured, readyState(model).vault)

        // The vault appears because something outside Nivara put it there, or because another
        // process created it; either way the screen must not keep its earlier answer.
        repository.state = VaultState.Ready(VaultIdentity.create(random), formatVersion = 1)
        model.onResumed()

        assertTrue(readyState(model).vault is VaultState.Ready)
        assertEquals("a read per resume, never a remembered state", 2, repository.inspectCalls)
    }

    @Test
    fun `a repository that throws is reported as unreachable, not as a crash`() = runTest {
        val model = viewModel(repository = FakeVaultRepository(VaultState.Missing, inspectThrows = true))

        assertEquals(VaultState.Unavailable, readyState(model).vault)
    }

    // ------------------------------------------------------------------ changing

    @Test
    fun `creating a vault with a session writes and reads back`() = runTest {
        val repository = FakeVaultRepository(VaultState.Missing)
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.initialize()

        assertEquals(listOf(false), repository.initializeRequests)
        assertTrue("the vault is read from storage, not assumed", readyState(model).vault is VaultState.Ready)
        assertEquals(R.string.vault_notice_initialized, readyState(model).noticeRes)
        assertNull(readyState(model).failure)
    }

    @Test
    fun `creating a vault without a session changes nothing and asks to unlock`() = runTest {
        val repository = FakeVaultRepository(VaultState.Missing)
        val model = viewModel(repository = repository, session = testSessionManager(clock))

        model.initialize()

        assertEquals("nothing may be written without a session", emptyList<Boolean>(), repository.initializeRequests)
        assertTrue(readyState(model).unlockRequired)
        assertEquals(R.string.vault_locked, readyState(model).failure?.textRes)
    }

    @Test
    fun `an expired session cannot create a vault`() = runTest {
        val repository = FakeVaultRepository(VaultState.Missing)
        val session = testSessionManager(clock)
        val model = viewModel(repository = repository, session = session)
        session.establish(AuthenticationOutcome.Succeeded)

        nowMillis += TEST_SESSION_TIMEOUT_MILLIS
        model.initialize()

        assertEquals(emptyList<Boolean>(), repository.initializeRequests)
        assertTrue(readyState(model).unlockRequired)
    }

    @Test
    fun `an unreadable vault is only replaced when that is what was asked for`() = runTest {
        val repository = FakeVaultRepository(VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged))
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.initialize()

        assertEquals("the ordinary control must never replace an unreadable vault", emptyList<Boolean>(), repository.initializeRequests)

        model.replaceUnreadable()

        assertEquals(listOf(true), repository.initializeRequests)
        assertTrue(readyState(model).vault is VaultState.Ready)
        assertEquals(R.string.vault_notice_initialized, readyState(model).noticeRes)
    }

    @Test
    fun `replacing is not offered for a vault that can be opened`() = runTest {
        val repository = FakeVaultRepository(VaultState.Ready(VaultIdentity.create(random), formatVersion = 1))
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.replaceUnreadable()

        assertEquals(emptyList<Boolean>(), repository.initializeRequests)
        assertNull("nothing was asked for, so nothing is reported", readyState(model).noticeRes)
    }

    @Test
    fun `a refused initialization is reported with its own message`() = runTest {
        val repository = FakeVaultRepository(
            VaultState.Missing,
            initializeResult = NivaraResult.Failure(VaultFailure.VerificationFailed),
        )
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.initialize()

        val state = readyState(model)
        assertEquals(R.string.vault_error_not_verified, state.failure?.textRes)
        assertNull("a refused write is not a confirmation", state.noticeRes)
        assertEquals("the state is what storage reports", VaultState.Missing, state.vault)
    }

    @Test
    fun `a failure with no typed reason is still reported`() = runTest {
        val repository = FakeVaultRepository(
            VaultState.Missing,
            initializeResult = NivaraResult.Failure(null),
        )
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.initialize()

        assertEquals(R.string.vault_error_selection_failed, readyState(model).failure?.textRes)
    }

    @Test
    fun `a repository that throws on a change is a failure, not a crash`() = runTest {
        val repository = FakeVaultRepository(VaultState.Missing, initializeThrows = true)
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.initialize()

        assertEquals(R.string.vault_error_selection_failed, readyState(model).failure?.textRes)
        assertNull(readyState(model).noticeRes)
    }

    @Test
    fun `a second change is refused while the first is being applied`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeVaultRepository(VaultState.Missing).apply { initializeGate = gate }
        val model = viewModel(repository = repository, session = authenticatedSession())

        model.initialize()
        assertTrue("a change in flight is visible to the screen", readyState(model).busy)

        model.initialize()

        assertEquals("one change at a time", listOf(false), repository.initializeRequests)

        gate.complete(Unit)
    }

    @Test
    fun `a message can be cleared without changing anything about the vault`() = runTest {
        val repository = FakeVaultRepository(
            VaultState.Missing,
            initializeResult = NivaraResult.Failure(VaultFailure.WriteFailed),
        )
        val model = viewModel(repository = repository, session = authenticatedSession())
        model.initialize()

        model.onMessageShown()

        assertNull(readyState(model).failure)
        assertNull(readyState(model).noticeRes)
        assertEquals(VaultState.Missing, readyState(model).vault)
    }

    // ------------------------------------------------------------------ choosing a root

    @Test
    fun `choosing a folder with a session stores it and reads the vault there`() = runTest {
        val repository = FakeVaultRepository(VaultState.NotConfigured)
        val locations = FakeVaultLocationStore()
        val model = viewModel(repository = repository, locations = locations, session = authenticatedSession())

        model.onRootSelected(rootReference)

        assertEquals(listOf(VaultLocation(rootReference)), locations.adopted)
        assertEquals("the new root is read before anything is said about it", 2, repository.inspectCalls)
        assertEquals(R.string.vault_notice_root_selected, readyState(model).noticeRes)
        assertNull(readyState(model).failure)
    }

    @Test
    fun `choosing a folder without a session stores nothing and asks to unlock`() = runTest {
        val locations = FakeVaultLocationStore()
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.NotConfigured),
            locations = locations,
            session = testSessionManager(clock),
        )

        model.onRootSelected(rootReference)

        assertEquals("a pick without a session may not change the configuration", emptyList<VaultLocation>(), locations.adopted)
        assertTrue(readyState(model).unlockRequired)
        assertEquals(R.string.vault_locked, readyState(model).failure?.textRes)
    }

    @Test
    fun `the folder chosen before unlocking is adopted exactly once after it`() = runTest {
        val locations = FakeVaultLocationStore()
        val session = testSessionManager(clock)
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.NotConfigured),
            locations = locations,
            session = session,
        )
        model.onRootSelected(rootReference)
        assertEquals("the pick waits for the gate", emptyList<VaultLocation>(), locations.adopted)

        session.establish(AuthenticationOutcome.Succeeded)
        model.onUnlockHandled()

        assertEquals(listOf(VaultLocation(rootReference)), locations.adopted)
        model.onUnlockHandled()
        assertEquals("and only once", 1, locations.adopted.size)
    }

    @Test
    fun `coming back from the credential screen without unlocking changes nothing`() = runTest {
        val locations = FakeVaultLocationStore()
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.NotConfigured),
            locations = locations,
            session = testSessionManager(clock),
        )
        model.onRootSelected(rootReference)

        model.onUnlockHandled()

        assertEquals(
            "a pick made while locked may not be applied because the screen came back",
            emptyList<VaultLocation>(),
            locations.adopted,
        )
        model.onUnlockHandled()
        assertEquals(
            "and the pick does not wait for a later unlock",
            emptyList<VaultLocation>(),
            locations.adopted,
        )
    }

    @Test
    fun `a selection Nivara cannot use is refused before it is remembered`() = runTest {
        val locations = FakeVaultLocationStore()
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.NotConfigured),
            locations = locations,
            session = authenticatedSession(),
        )

        model.onRootSelected("")

        assertEquals(
            "nothing unusable ever reaches the location store",
            emptyList<VaultLocation>(),
            locations.adopted,
        )
        assertEquals(
            "and the screen says the selection failed",
            R.string.vault_error_selection_failed,
            readyState(model).failure?.textRes,
        )
    }

    @Test
    fun `a refused selection is reported and the previous root is kept`() = runTest {
        val locations = FakeVaultLocationStore(storeResult = NivaraResult.Failure(VaultFailure.AccessDenied))
        val repository = FakeVaultRepository(VaultState.NotConfigured)
        val model = viewModel(repository = repository, locations = locations, session = authenticatedSession())

        model.onRootSelected(rootReference)

        assertEquals(R.string.vault_error_selection_failed, readyState(model).failure?.textRes)
        assertNull("a refused selection is not a confirmation", readyState(model).noticeRes)
        assertEquals("the previous root is what the screen still shows", VaultState.NotConfigured, readyState(model).vault)
    }

    @Test
    fun `a root that cannot be read is never replaced by choosing another one`() = runTest {
        val locations = FakeVaultLocationStore(stored = VaultLocationRead.Unreadable)
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.LocationUnknown),
            locations = locations,
            session = authenticatedSession(),
        )

        val state = readyState(model)
        assertEquals(VaultState.LocationUnknown, state.vault)
        assertFalse("nothing is created while the vault's location is unknown", state.canInitialize)
        assertFalse(state.canReplaceUnreadable)
        assertEquals(
            "and Nivara does not clear the record it could not read",
            VaultLocationRead.Unreadable,
            locations.stored,
        )
    }

    // ------------------------------------------------------------------ the session

    @Test
    fun `the screen never opens, extends or ends a session`() = runTest {
        val session = RecordingSessionManager(authenticatedSession())
        val locations = FakeVaultLocationStore()
        val model = viewModel(
            repository = FakeVaultRepository(VaultState.Missing),
            locations = locations,
            session = session,
        )

        model.initialize()
        model.onRootSelected(rootReference)
        model.onResumed()

        assertEquals("the gate is asked, never written", 0, session.establishCalls)
        assertEquals(0, session.lockNowCalls)
        assertTrue("its authoritative read is what is used", session.currentStateCalls > 0)
    }

    @Test
    fun `a session opened by biometrics is enough, with no second authentication`() = runTest {
        val session = testSessionManager(clock)
        val model = viewModel(repository = FakeVaultRepository(VaultState.Missing), session = session)
        session.establish(BiometricAuthenticationOutcome.Succeeded)

        assertTrue(readyState(model).sessionAuthenticated)
        model.initialize()

        assertTrue(readyState(model).vault is VaultState.Ready)
    }

    @Test
    fun `quick lock puts the vault back behind the gate`() = runTest {
        val session = testSessionManager(clock)
        val repository = FakeVaultRepository(VaultState.Missing)
        val model = viewModel(repository = repository, session = session)
        session.establish(AuthenticationOutcome.Succeeded)
        assertTrue(readyState(model).canInitialize)

        session.lockNow()

        val state = readyState(model)
        assertFalse(state.sessionAuthenticated)
        assertFalse(state.canInitialize)
        model.initialize()
        assertEquals("a locked screen writes nothing", emptyList<Boolean>(), repository.initializeRequests)
        assertTrue(readyState(model).unlockRequired)
    }

    @Test
    fun `a restarted screen starts with no session and no remembered vault`() = runTest {
        val model = viewModel(repository = FakeVaultRepository(VaultState.Missing))

        val state = readyState(model)
        assertFalse("nothing authenticated is reconstructed", state.sessionAuthenticated)
        assertFalse(state.canInitialize)
    }

    // ------------------------------------------------------------------ helpers

    private fun authenticatedSession(): SessionManager =
        testSessionManager(clock).apply { establish(AuthenticationOutcome.Succeeded) }

    private fun viewModel(
        repository: VaultRepository,
        index: VaultIndexRepository = FakeVaultIndexRepository(),
        albums: VaultOrganizationRepository = FakeVaultOrganizationRepository(),
        trash: FakeVaultTrashRepository = FakeVaultTrashRepository(),
        locations: FakeVaultLocationStore = FakeVaultLocationStore(),
        session: SessionManager = testSessionManager(clock),
    ): VaultViewModel = VaultViewModel(
        vaultRepository = repository,
        indexRepository = index,
        organizationRepository = albums,
        trashRepository = trash,
        locationStore = locations,
        sessionManager = session,
    )

    private fun readyState(viewModel: VaultViewModel): VaultUiState.Ready =
        viewModel.uiState.value as? VaultUiState.Ready
            ?: error("the screen never left its loading state")

    /**
     * A list of files the test controls.
     *
     * Reading reports whatever the test set; importing records the request and reports the result the
     * test chose, so the screen's handling of a committed import, a refused one and an interrupted
     * session can all be driven without a vault on storage.
     */
    private class FakeVaultIndexRepository(
        var state: VaultIndexState = VaultIndexState.Missing,
        private val importResult: NivaraResult<VaultItem> =
            NivaraResult.Failure(VaultImportFailure.StorageUnavailable),
    ) : VaultIndexRepository {

        var readCalls: Int = 0
        val imported: MutableList<VaultSourceReference> = mutableListOf()
        var importGate: CompletableDeferred<Unit>? = null
        var importThrows: Boolean = false
        var observedAuthorize: Boolean? = null

        override suspend fun read(): VaultIndexState {
            readCalls += 1
            return state
        }

        override suspend fun importFile(
            source: VaultSourceReference,
            authorize: () -> Boolean,
            onProgress: (VaultImportProgress) -> Unit,
        ): NivaraResult<VaultItem> {
            imported += source
            importGate?.await()
            if (importThrows) throw IllegalStateException("the storage is gone")
            observedAuthorize = authorize()
            return importResult
        }
    }

    /**
     * A repository whose state the test controls.
     *
     * A successful initialization changes the state it reports, which is what the real repository
     * does: the screen reads the vault back from storage instead of trusting the call.
     */
    private class FakeVaultRepository(
        var state: VaultState = VaultState.NotConfigured,
        private val initializeResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
        private val inspectThrows: Boolean = false,
        private val initializeThrows: Boolean = false,
    ) : VaultRepository {

        val initializeRequests: MutableList<Boolean> = mutableListOf()
        var inspectCalls: Int = 0
        var initializeGate: CompletableDeferred<Unit>? = null

        override suspend fun inspect(): VaultState {
            inspectCalls += 1
            if (inspectThrows) throw IllegalStateException("the storage is gone")
            return state
        }

        override suspend fun initialize(replaceUnreadable: Boolean): NivaraResult<Unit> {
            initializeRequests += replaceUnreadable
            initializeGate?.await()
            if (initializeThrows) throw IllegalStateException("the storage is gone")
            if (initializeResult is NivaraResult.Success) {
                state = VaultState.Ready(
                    identity = VaultIdentity.create(SecureRandomGenerator()),
                    formatVersion = 1,
                )
            }
            return initializeResult
        }
    }

    /** Counts what the screen asks of the gate, and what it never asks of it. */
    private class RecordingSessionManager(private val delegate: SessionManager) : SessionManager {

        var currentStateCalls: Int = 0
        var establishCalls: Int = 0
        var lockNowCalls: Int = 0

        override val state = delegate.state

        override fun establish(outcome: AuthenticationOutcome): SessionState {
            establishCalls += 1
            return delegate.establish(outcome)
        }

        override fun establish(outcome: BiometricAuthenticationOutcome): SessionState {
            establishCalls += 1
            return delegate.establish(outcome)
        }

        override fun currentState(): SessionState {
            currentStateCalls += 1
            return delegate.currentState()
        }

        override fun lockNow(): SessionState {
            lockNowCalls += 1
            return delegate.lockNow()
        }
    }
}
