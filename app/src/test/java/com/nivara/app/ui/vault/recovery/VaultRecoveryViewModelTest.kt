package com.nivara.app.ui.vault.recovery

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultRecoveryFailure
import com.nivara.app.domain.vault.VaultRecoverySurvey
import com.nivara.app.domain.vault.displayFingerprint
import com.nivara.app.testing.FakeVaultRecoveryRepository
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
 * Local JVM tests for the recovery screen's state machine.
 *
 * What is pinned here is the discipline of the flow: nothing is surveyed until the user picks a
 * folder, nothing is adopted by a survey, a refusal stays a refusal (never an empty vault, never a
 * silent fallback), the code goes to the repository exactly as entered, and a successful
 * reconnection is reported as the vault's own fingerprint — never as a session and never as an
 * authentication.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultRecoveryViewModelTest {

    private val reference = "content://com.android.externalstorage.documents/tree/primary%3ANivara"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repository: FakeVaultRecoveryRepository = FakeVaultRecoveryRepository(),
    ): Pair<VaultRecoveryViewModel, FakeVaultRecoveryRepository> =
        VaultRecoveryViewModel(recoveryRepository = repository) to repository

    // ------------------------------------------------------------------ the first look

    @Test
    fun `the screen starts before any folder is chosen`() {
        val (model, repository) = viewModel()

        val state = model.uiState.value

        assertEquals(VaultRecoveryPhase.SelectLocation, state.phase)
        assertFalse(state.busy)
        assertNull(state.failure)
        assertTrue("nothing is surveyed on its own", repository.surveyed.isEmpty())
    }

    @Test
    fun `a selection that is not a location is refused before any survey`() {
        val (model, repository) = viewModel()

        model.onLocationPicked("")

        assertEquals(VaultRecoveryPhase.SelectLocation, model.uiState.value.phase)
        assertEquals(
            R.string.vault_recovery_location_unavailable,
            model.uiState.value.failure?.textRes,
        )
        assertTrue(repository.surveyed.isEmpty())
    }

    @Test
    fun `a folder that is not a vault is said as that`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(VaultRecoverySurvey.NotAVault),
        )
        val (model, _) = viewModel(repository)

        model.onLocationPicked(reference)

        assertEquals(VaultRecoveryPhase.NotAVault, model.uiState.value.phase)
        assertFalse(model.uiState.value.busy)
        assertEquals(1, repository.surveyed.size)
    }

    @Test
    fun `a vault without recovery material is said as that`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(VaultRecoverySurvey.RecoveryNotSetUp),
        )
        val (model, _) = viewModel(repository)

        model.onLocationPicked(reference)

        assertEquals(VaultRecoveryPhase.RecoveryNotSetUp, model.uiState.value.phase)
    }

    @Test
    fun `a damaged recovery record is said as damage`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(VaultRecoverySurvey.VaultDamaged),
        )
        val (model, _) = viewModel(repository)

        model.onLocationPicked(reference)

        assertEquals(VaultRecoveryPhase.VaultDamaged, model.uiState.value.phase)
    }

    @Test
    fun `a newer recovery record is said as unsupported`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(VaultRecoverySurvey.VaultUnsupported),
        )
        val (model, _) = viewModel(repository)

        model.onLocationPicked(reference)

        assertEquals(VaultRecoveryPhase.VaultUnsupported, model.uiState.value.phase)
    }

    @Test
    fun `a vault recovery can work with asks for the code and shows the fingerprint`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
        )
        val (model, _) = viewModel(repository)

        model.onLocationPicked(reference)

        val phase = model.uiState.value.phase
        assertTrue(phase is VaultRecoveryPhase.RecoveryRequired)
        assertEquals("abcd ef01", (phase as VaultRecoveryPhase.RecoveryRequired).identityFingerprint)
    }

    @Test
    fun `a survey the storage refuses is unavailability, not absence`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Failure(VaultRecoveryFailure.LocationUnavailable),
        )
        val (model, _) = viewModel(repository)

        model.onLocationPicked(reference)

        assertEquals(VaultRecoveryPhase.LocationUnavailable, model.uiState.value.phase)
        assertEquals(
            R.string.vault_recovery_location_unavailable,
            model.uiState.value.failure?.textRes,
        )
    }

    // ------------------------------------------------------------------ the attempt

    @Test
    fun `the code is handed to the repository exactly as entered`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
            recoverResult = NivaraResult.Success(VaultIdentity("00112233445566778899aabbccddeeff")),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)

        model.onCodeChanged("AAAA-BBBB-CCCC")
        model.onRecoverRequested()

        assertEquals(1, repository.recovered.size)
        assertEquals("AAAA-BBBB-CCCC", repository.recovered.single().second)
    }

    @Test
    fun `a successful reconnection is reported with the vault's fingerprint`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
            recoverResult = NivaraResult.Success(VaultIdentity("00112233445566778899aabbccddeeff")),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)
        model.onCodeChanged("AAAA-BBBB-CCCC")

        model.onRecoverRequested()

        val phase = model.uiState.value.phase
        assertTrue(phase is VaultRecoveryPhase.Reconnected)
        assertEquals(
            VaultIdentity("00112233445566778899aabbccddeeff").displayFingerprint(),
            (phase as VaultRecoveryPhase.Reconnected).identityFingerprint,
        )
        assertEquals("the entered code leaves the state once recovery succeeds", "", model.uiState.value.codeInput)
    }

    @Test
    fun `a wrong secret keeps the screen at the code with its own words`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
            recoverResult = NivaraResult.Failure(VaultRecoveryFailure.WrongMaterial),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)
        model.onCodeChanged("AAAA-BBBB-CCCC")

        model.onRecoverRequested()

        val state = model.uiState.value
        assertTrue("the attempt stays where it was made", state.phase is VaultRecoveryPhase.RecoveryRequired)
        assertEquals(R.string.vault_recovery_wrong_material, state.failure?.textRes)
    }

    @Test
    fun `a lockout is drawn with the seconds it lasts`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
            recoverResult = NivaraResult.Failure(VaultRecoveryFailure.Locked(remainingMillis = 4_200)),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)
        model.onCodeChanged("AAAA-BBBB-CCCC")

        model.onRecoverRequested()

        val failure = model.uiState.value.failure
        assertEquals(R.string.vault_recovery_locked, failure?.textRes)
        assertEquals("the delay is rounded up to whole seconds", 5L, failure?.argument)
    }

    @Test
    fun `recovery is not attempted without a surveyed folder`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            recoverResult = NivaraResult.Success(VaultIdentity("00112233445566778899aabbccddeeff")),
        )
        val (model, _) = viewModel(repository)

        model.onCodeChanged("AAAA-BBBB-CCCC")
        model.onRecoverRequested()

        assertTrue("no folder, no attempt", repository.recovered.isEmpty())
    }

    @Test
    fun `recovery is not attempted before the survey found a recoverable vault`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(VaultRecoverySurvey.NotAVault),
            recoverResult = NivaraResult.Success(VaultIdentity("00112233445566778899aabbccddeeff")),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)
        model.onCodeChanged("AAAA-BBBB-CCCC")

        model.onRecoverRequested()

        assertTrue(repository.recovered.isEmpty())
    }

    @Test
    fun `the attempt can be repeated after a refusal`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
            recoverResult = NivaraResult.Failure(VaultRecoveryFailure.WrongMaterial),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)
        model.onCodeChanged("AAAA-BBBB-CCCC")

        model.onRecoverRequested()
        model.onRecoverRequested()

        assertEquals("a refusal does not consume the attempt", 2, repository.recovered.size)
    }

    // ------------------------------------------------------------------ starting over

    @Test
    fun `starting over drops the folder and the code`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)
        model.onCodeChanged("AAAA-BBBB-CCCC")

        model.onRestartRequested()

        val state = model.uiState.value
        assertEquals(VaultRecoveryPhase.SelectLocation, state.phase)
        assertEquals("", state.codeInput)

        model.onRecoverRequested()
        assertTrue("the dropped folder cannot be recovered against", repository.recovered.isEmpty())
    }

    @Test
    fun `typing the code clears the previous refusal`() = runTest {
        val repository = FakeVaultRecoveryRepository(
            surveyResult = NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(identityFingerprint = "abcd ef01"),
            ),
            recoverResult = NivaraResult.Failure(VaultRecoveryFailure.WrongMaterial),
        )
        val (model, _) = viewModel(repository)
        model.onLocationPicked(reference)
        model.onCodeChanged("AAAA-BBBB-CCCC")
        model.onRecoverRequested()

        model.onCodeChanged("AAAA-BBBB-CCCD")

        assertNull(model.uiState.value.failure)
    }
}
