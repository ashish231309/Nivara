package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.FakeVaultLocationStore
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultContentDigest
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadableReason
import com.nivara.app.testing.FakeVaultOrganizationRepository
import com.nivara.app.testing.FakeVaultTrashRepository
import com.nivara.app.testing.testSessionManager
import com.nivara.app.testing.FakeVaultRecoveryRepository
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
 * Local JVM tests for importing a file from the vault screen.
 *
 * The screen is where an import begins, so this is where the two rules that protect the user are
 * kept: nothing is imported without an open session, and a file is shown as imported only after the
 * repository says so — never because a call was made. What is checked here is the screen's half of
 * that contract: which taps are honoured, what is drawn while an encryption runs, and what is said
 * when it does not finish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultImportTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val clock = TimeProvider { 1_000L }
    private val random = SecureRandomGenerator()
    private val session: SessionManager = testSessionManager(clock)

    private val sourceReference = "content://com.android.providers.downloads.documents/document/42"

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a file picked while the gate is closed is not imported`() = runTest {
        val index = FakeVaultIndexRepository()
        val model = viewModel(index = index)
        session.lockNow()

        model.onFileSelected(sourceReference)

        assertTrue("nothing may be imported without a session", index.imported.isEmpty())
        val state = readyState(model)
        assertTrue(state.unlockRequired)
        assertEquals(R.string.vault_import_locked, state.failure?.textRes)
    }

    @Test
    fun `a selection the vault cannot use is refused before anything is attempted`() = runTest {
        val index = FakeVaultIndexRepository()
        val model = viewModel(index = index)

        model.onFileSelected("   ")

        assertTrue(index.imported.isEmpty())
        val state = readyState(model)
        assertFalse(state.unlockRequired)
        assertEquals(R.string.vault_import_error_selection_failed, state.failure?.textRes)
    }

    @Test
    fun `an import with an open session is reported only after the repository committed it`() = runTest {
        val item = item("notes.txt")
        val index = FakeVaultIndexRepository(
            state = VaultIndexState.Ready(items = listOf(item)),
            importResult = NivaraResult.Success(item),
        )
        val model = viewModel(index = index)
        openSession()

        model.onFileSelected(sourceReference)

        assertEquals(1, index.imported.size)
        assertEquals(sourceReference, index.imported.single().value)
        assertEquals("the list is read again after the import", 2, index.readCalls)
        val state = readyState(model)
        assertEquals(R.string.vault_notice_imported, state.noticeRes)
        assertNull(state.failure)
        assertFalse(state.importing)
        assertFalse(state.busy)
        val list = state.index as VaultIndexUiState.Indexed
        assertEquals(listOf("notes.txt"), list.items.map { shown -> shown.name })
    }

    @Test
    fun `a failed import says the file is not in the vault`() = runTest {
        val index = FakeVaultIndexRepository(
            importResult = NivaraResult.Failure(VaultImportFailure.SourceUnavailable),
        )
        val model = viewModel(index = index)
        openSession()

        model.onFileSelected(sourceReference)

        val state = readyState(model)
        assertEquals(R.string.vault_import_error_source_unavailable, state.failure?.textRes)
        assertNull(state.noticeRes)
        assertEquals(VaultIndexUiState.Empty, state.index)
    }

    @Test
    fun `an import refused because the session closed asks the user to unlock and is not retried`() = runTest {
        val index = FakeVaultIndexRepository(
            importResult = NivaraResult.Failure(VaultImportFailure.NotAuthorized),
        )
        val model = viewModel(index = index)
        openSession()

        model.onFileSelected(sourceReference)

        val state = readyState(model)
        assertTrue(state.unlockRequired)
        assertEquals(R.string.vault_import_error_not_authorized, state.failure?.textRes)
        assertEquals(1, index.imported.size)
    }

    @Test
    fun `an import reports progress while it runs and does not offer a second one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val index = FakeVaultIndexRepository(
            importResult = NivaraResult.Success(item("video.mp4")),
        ).apply {
            importGate = gate
            progressSteps = listOf(300_000L, 600_000L)
            declaredSize = 1_000_000L
        }
        val model = viewModel(index = index)
        openSession()

        model.onFileSelected(sourceReference)

        val running = readyState(model)
        assertTrue("the screen knows an import is running", running.importing)
        assertTrue(running.busy)
        assertFalse("a second import is not offered", running.canImport)
        assertEquals(600_000L, running.progress?.bytesProcessed)
        assertEquals(1_000_000L, running.progress?.totalBytes)

        val during = model.uiState.value as VaultUiState.Ready
        assertTrue(during.importing)

        gate.complete(Unit)
        val finished = readyState(model)
        assertFalse(finished.importing)
        assertNull(finished.progress)
        assertEquals(R.string.vault_notice_imported, finished.noticeRes)
    }

    @Test
    fun `a second selection is ignored while an import is running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val index = FakeVaultIndexRepository(
            importResult = NivaraResult.Success(item("a.txt")),
        ).apply { importGate = gate }
        val model = viewModel(index = index)
        openSession()

        model.onFileSelected(sourceReference)
        model.onFileSelected("content://com.android.providers.downloads.documents/document/43")

        assertEquals(1, index.imported.size)
        gate.complete(Unit)
        assertEquals(1, index.imported.size)
    }

    @Test
    fun `a repository that throws is reported as a failed import, never as an imported file`() = runTest {
        val index = FakeVaultIndexRepository().apply { importThrows = true }
        val model = viewModel(index = index)
        openSession()

        model.onFileSelected(sourceReference)

        val state = readyState(model)
        assertEquals(R.string.vault_import_error_generic, state.failure?.textRes)
        assertNull(state.noticeRes)
    }

    @Test
    fun `importing is offered exactly when the vault and its list can be used`() = runTest {
        val model = viewModel(index = FakeVaultIndexRepository(state = VaultIndexState.Missing))
        openSession()

        assertTrue("an empty vault can be imported into", readyState(model).canImport)
    }

    @Test
    fun `the action is not offered for a vault that cannot be opened or a list that cannot be read`() {
        val vaultReady = VaultState.Ready(identity = com.nivara.app.domain.vault.VaultIdentity("00112233445566778899aabbccddeeff"), formatVersion = 1)

        assertFalse("no vault, no import", ready(vault = VaultState.Missing, index = VaultIndexUiState.Empty).canImport)
        assertFalse(
            "an unreadable vault is not a place to write",
            ready(vault = VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged), index = VaultIndexUiState.Empty).canImport,
        )
        assertFalse(
            "a list that cannot be read is not an empty list",
            ready(vault = vaultReady, index = VaultIndexUiState.Unreadable(VaultIndexUnreadable.MetadataDamaged)).canImport,
        )
        assertFalse(
            "a newer list is never written over",
            ready(vault = vaultReady, index = VaultIndexUiState.UnsupportedVersion).canImport,
        )
        assertFalse(
            "an unreachable list is not an empty one",
            ready(vault = vaultReady, index = VaultIndexUiState.Unavailable).canImport,
        )
        assertFalse(
            "no session, no import",
            ready(vault = vaultReady, index = VaultIndexUiState.Empty, sessionAuthenticated = false).canImport,
        )
        assertFalse(
            "one change at a time",
            ready(vault = vaultReady, index = VaultIndexUiState.Empty, busy = true).canImport,
        )
        assertTrue(ready(vault = vaultReady, index = VaultIndexUiState.Empty).canImport)
        assertTrue(
            ready(
                vault = vaultReady,
                index = VaultIndexUiState.Indexed(
                    items = listOf(
                        VaultItemUi(
                            id = VaultItemId.create(random),
                            name = "a.txt",
                            kind = VaultContentKind.Document,
                            sizeBytes = 1L,
                            importedAtEpochMillis = 1L,
                            mimeType = "text/plain",
                        ),
                    ),
                ),
            ).canImport,
        )
    }

    @Test
    fun `the list is drawn as its own state, never as emptiness`() = runTest {
        val index = FakeVaultIndexRepository(
            state = VaultIndexState.Unreadable(VaultIndexUnreadable.KeyUnavailable),
        )
        val model = viewModel(index = index)

        val state = readyState(model)

        assertEquals(VaultIndexUiState.Unreadable(VaultIndexUnreadable.KeyUnavailable), state.index)
        assertFalse(state.canImport)
    }

    // ------------------------------------------------------------------ helpers

    private fun viewModel(
        index: VaultIndexRepository,
        repository: VaultRepository = ReadyVaultRepository(),
    ): VaultViewModel = VaultViewModel(
        vaultRepository = repository,
        indexRepository = index,
        organizationRepository = FakeVaultOrganizationRepository(),
        trashRepository = FakeVaultTrashRepository(),
        recoveryRepository = FakeVaultRecoveryRepository(),
        locationStore = FakeVaultLocationStore(),
        sessionManager = session,
    )

    /** Opens the existing gate the way the credential screen does — the tests never fake a session. */
    private fun openSession() {
        session.establish(AuthenticationOutcome.Succeeded)
    }

    private fun readyState(viewModel: VaultViewModel): VaultUiState.Ready =
        viewModel.uiState.value as? VaultUiState.Ready
            ?: error("the screen never left its loading state")

    private fun ready(
        vault: VaultState,
        index: VaultIndexUiState,
        sessionAuthenticated: Boolean = true,
        busy: Boolean = false,
    ): VaultUiState.Ready = VaultUiState.Ready(
        vault = vault,
        index = index,
        sessionAuthenticated = sessionAuthenticated,
        busy = busy,
    )

    private fun item(name: String): VaultItem = VaultItem(
        id = VaultItemId.create(random),
        name = name,
        mimeType = "text/plain",
        sizeBytes = 42L,
        importedAtEpochMillis = 1_000L,
        contentFormatVersion = 1,
        contentDigest = VaultContentDigest.fromBytes(ByteArray(32) { 3 })!!,
    )

    private class ReadyVaultRepository : VaultRepository {

        override suspend fun inspect(): VaultState = VaultState.Ready(
            identity = com.nivara.app.domain.vault.VaultIdentity("00112233445566778899aabbccddeeff"),
            formatVersion = 1,
        )

        override suspend fun initialize(replaceUnreadable: Boolean): NivaraResult<Unit> =
            NivaraResult.Failure(com.nivara.app.domain.vault.VaultFailure.VaultAlreadyExists)
    }

    private class FakeVaultIndexRepository(
        var state: VaultIndexState = VaultIndexState.Missing,
        private val importResult: NivaraResult<VaultItem> =
            NivaraResult.Failure(VaultImportFailure.StorageUnavailable),
    ) : VaultIndexRepository {

        var readCalls: Int = 0
        val imported: MutableList<VaultSourceReference> = mutableListOf()
        var importGate: CompletableDeferred<Unit>? = null
        var importThrows: Boolean = false
        var progressSteps: List<Long> = emptyList()
        var declaredSize: Long? = null

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
            progressSteps.forEach { processed ->
                onProgress(VaultImportProgress(bytesProcessed = processed, totalBytes = declaredSize))
            }
            importGate?.await()
            if (importThrows) throw IllegalStateException("the storage is gone")
            return importResult
        }
    }
}
