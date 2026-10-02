package com.nivara.app.ui.vault.recovery

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.FakeReadyVaultRepository
import com.nivara.app.data.vault.FakeVaultLocationStore
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.vault.RecoveryStatus
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationRead
import com.nivara.app.domain.vault.VaultRecoveryFailure
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.testing.FakeVaultOrganizationRepository
import com.nivara.app.testing.FakeVaultRecoveryRepository
import com.nivara.app.testing.FakeVaultTrashRepository
import com.nivara.app.testing.testSessionManager
import com.nivara.app.ui.vault.VaultRecoveryCard
import com.nivara.app.ui.vault.VaultUiState
import com.nivara.app.ui.vault.VaultViewModel
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
 * Local JVM tests for the vault screen's recovery setup: the card that says a connected vault has
 * no way back in yet, the write that gives it one, and the one moment the code exists on screen.
 *
 * What is pinned here is the separation recovery exists to keep: setup is a change like any other
 * on the vault screen — gated by the existing session, written through the recovery repository,
 * reported in typed words — and the code it produces is shown once, cleared on acknowledgment, and
 * never confused with being authenticated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultRecoverySetupViewModelTest {

    private val location = VaultLocation("content://com.android.externalstorage.documents/tree/primary%3ANivara")
    private var now: Long = 1_700_000_000_000L
    private val clock = TimeProvider { now }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FixedIndexRepository(
        private val state: () -> VaultIndexState,
    ) : VaultIndexRepository {

        override suspend fun read(): VaultIndexState = state()

        override suspend fun importFile(
            source: VaultSourceReference,
            authorize: () -> Boolean,
            onProgress: (VaultImportProgress) -> Unit,
        ): NivaraResult<VaultItem> = NivaraResult.Failure(VaultFailure.StorageUnavailable)
    }

    private fun openSession(session: SessionManager) {
        session.establish(AuthenticationOutcome.Succeeded)
    }

    private fun viewModel(
        vault: VaultRepository = FakeReadyVaultRepository(),
        recovery: FakeVaultRecoveryRepository = FakeVaultRecoveryRepository(),
        session: SessionManager = testSessionManager(clock),
    ): VaultViewModel = VaultViewModel(
        vaultRepository = vault,
        indexRepository = FixedIndexRepository { VaultIndexState.Ready(items = emptyList()) },
        organizationRepository = FakeVaultOrganizationRepository(),
        trashRepository = FakeVaultTrashRepository(),
        recoveryRepository = recovery,
        locationStore = FakeVaultLocationStore(VaultLocationRead.Present(location)),
        sessionManager = session,
    )

    private fun readyState(viewModel: VaultViewModel): VaultUiState.Ready =
        viewModel.uiState.value as VaultUiState.Ready

    // ------------------------------------------------------------------ the card

    @Test
    fun `a connected vault without recovery material shows the setup card`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)

        val state = readyState(model)

        assertEquals(VaultRecoveryCard.NotSetUp, state.recoveryCard)
        assertTrue("the gate is open and the vault is ready", state.canSetUpRecovery)
    }

    @Test
    fun `a vault that already has recovery material is not prompted`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.SetUp),
        )
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)

        val state = readyState(model)

        assertEquals(VaultRecoveryCard.SetUp, state.recoveryCard)
        assertFalse(state.canSetUpRecovery)
    }

    @Test
    fun `a damaged recovery record offers replacement`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.Damaged),
        )
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)

        val state = readyState(model)

        assertEquals(VaultRecoveryCard.Damaged, state.recoveryCard)
        assertTrue(state.canSetUpRecovery)
    }

    @Test
    fun `a status the repository cannot report is drawn as damaged, not as set up`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Failure(VaultFailure.StorageUnavailable),
        )
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)

        assertEquals(VaultRecoveryCard.Damaged, readyState(model).recoveryCard)
    }

    @Test
    fun `a vault that is not ready shows no recovery card`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        val model = viewModel(
            vault = FakeReadyVaultRepository(state = VaultState.Missing),
            recovery = recovery,
        )

        assertEquals(VaultRecoveryCard.Hidden, readyState(model).recoveryCard)
        assertFalse(readyState(model).canSetUpRecovery)
    }

    @Test
    fun `the card is not offered while the gate is closed`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        val model = viewModel(recovery = recovery)

        assertFalse(readyState(model).canSetUpRecovery)
    }

    // ------------------------------------------------------------------ the write

    @Test
    fun `setup without a session asks for the existing gate`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        val model = viewModel(recovery = recovery)

        model.onSetUpRecoveryRequested()

        val state = readyState(model)
        assertTrue("the screen asks for the credential screen, not a bypass", state.unlockRequired)
        assertEquals(0, recovery.setUpCalls)
    }

    @Test
    fun `setup shows the code in state and marks the vault set up`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        recovery.setUpCode = "AAAA-BBBB-CCCC-DDDD"
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)

        model.onSetUpRecoveryRequested()

        val state = readyState(model)
        assertEquals("AAAA-BBBB-CCCC-DDDD", state.recoveryCode)
        assertEquals(VaultRecoveryCard.SetUp, state.recoveryCard)
        assertFalse(state.recoverySetupBusy)
        assertEquals(1, recovery.setUpCalls)
    }

    @Test
    fun `acknowledging the code clears it from the screen`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        recovery.setUpCode = "AAAA-BBBB-CCCC-DDDD"
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)
        model.onSetUpRecoveryRequested()

        model.onRecoveryCodeAcknowledged()

        assertNull("the code exists for one moment only", readyState(model).recoveryCode)
    }

    @Test
    fun `acknowledging twice changes nothing`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        recovery.setUpCode = "AAAA-BBBB-CCCC-DDDD"
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)
        model.onSetUpRecoveryRequested()
        model.onRecoveryCodeAcknowledged()

        model.onRecoveryCodeAcknowledged()

        assertNull(readyState(model).recoveryCode)
    }

    @Test
    fun `a failed setup says so and shows no code`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        recovery.setUpFailure = VaultRecoveryFailure.WriteFailed
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)

        model.onSetUpRecoveryRequested()

        val state = readyState(model)
        assertNull(state.recoveryCode)
        assertEquals(R.string.vault_error_recovery_failed, state.failure?.textRes)
    }

    @Test
    fun `a vault that closed meanwhile is refused as not ready`() = runTest {
        val recovery = FakeVaultRecoveryRepository(
            statusResult = NivaraResult.Success(RecoveryStatus.NotSetUp),
        )
        recovery.setUpFailure = VaultRecoveryFailure.VaultNotReady
        val session = testSessionManager(clock)
        val model = viewModel(recovery = recovery, session = session)
        openSession(session)

        model.onSetUpRecoveryRequested()

        assertEquals(R.string.vault_error_recovery_not_ready, readyState(model).failure?.textRes)
        assertNull(readyState(model).recoveryCode)
    }
}
