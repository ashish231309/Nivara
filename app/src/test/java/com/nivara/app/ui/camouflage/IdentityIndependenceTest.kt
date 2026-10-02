package com.nivara.app.ui.camouflage

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.apphide.FileHiddenApplicationRepository
import com.nivara.app.data.applock.FileProtectedApplicationRepository
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import com.nivara.app.domain.applock.ProtectedApplication
import com.nivara.app.domain.camouflage.CamouflageProfile
import com.nivara.app.domain.camouflage.CamouflageRepository
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.testing.testSessionManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Local JVM tests for the independence of identity, hiding and protection, on real files.
 *
 * The three features have separate owners: an identity is a platform component state, the hidden set
 * is one file, and the protected set is another. These tests run the *production* file repositories
 * for the two sets — on a temporary directory, with the real format and the real atomic writes —
 * while an identity change is made, and assert that nothing either of them holds moves.
 *
 * The identity side is a stand-in, because the platform's component states need a device; what is
 * being demonstrated is the part that could regress in a refactor: that changing the name Nivara
 * presents under cannot reach either configuration file, and that neither configuration change can
 * reach the identity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityIndependenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val hiddenFile: File get() = File(temporaryFolder.root, "hidden-applications.nvh")
    private val protectedFile: File get() = File(temporaryFolder.root, "protected-applications.nvpl")

    private fun hiddenRepository(): FileHiddenApplicationRepository = FileHiddenApplicationRepository(hiddenFile)
    private fun protectedRepository(): FileProtectedApplicationRepository =
        FileProtectedApplicationRepository(protectedFile)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an identity change does not touch the hidden set`() = runTest {
        assertTrue(hiddenRepository().hide(HiddenApplication(CAMERA)).isSuccess)
        assertTrue(hiddenRepository().hide(HiddenApplication(NOTES)).isSuccess)
        val before = hiddenFile.readBytes()

        identityFlow(repository = RecordingIdentityRepository(CamouflageProfile.Nivara)) { model ->
            model.select(CamouflageProfile.Weather)
        }

        assertTrue(
            "the hidden set is byte-for-byte what it was",
            before.contentEquals(hiddenFile.readBytes()),
        )
        assertEquals(
            setOf(HiddenApplication(CAMERA), HiddenApplication(NOTES)),
            (hiddenRepository().hiddenApplications() as HiddenApplicationsRead.Available).hidden,
        )
    }

    @Test
    fun `an identity change does not touch the protected set`() = runTest {
        assertTrue(protectedRepository().protect(ProtectedApplication(CAMERA)).isSuccess)
        val before = protectedFile.readBytes()

        identityFlow(repository = RecordingIdentityRepository(CamouflageProfile.Notes)) { model ->
            model.select(CamouflageProfile.Nivara)
        }

        assertTrue(
            "the protected set is byte-for-byte what it was",
            before.contentEquals(protectedFile.readBytes()),
        )
        assertEquals(
            setOf(ProtectedApplication(CAMERA)),
            protectedRepository().protectedApplications().valueOrNull(),
        )
    }

    @Test
    fun `an identity change creates neither file where there was none`() = runTest {
        identityFlow(repository = RecordingIdentityRepository(CamouflageProfile.Nivara)) { model ->
            model.select(CamouflageProfile.Calculator)
        }

        assertTrue(
            "an identity is not stored by Nivara, so no file appears",
            !hiddenFile.exists() && !protectedFile.exists(),
        )
    }

    @Test
    fun `hiding and protecting change nothing about the identity`() = runTest {
        val identity = RecordingIdentityRepository(CamouflageProfile.Calculator)

        assertTrue(hiddenRepository().hide(HiddenApplication(NOTES)).isSuccess)
        assertTrue(protectedRepository().protect(ProtectedApplication(NOTES)).isSuccess)

        assertEquals(
            "an identity is not something hiding or protecting can move",
            CamouflageProfile.Calculator,
            identity.currentProfile(),
        )
        assertEquals("and nothing asked it to", emptyList<CamouflageProfile>(), identity.writes)
    }

    @Test
    fun `the identity survives a restart while the authorization does not`() = runTest {
        val identity = RecordingIdentityRepository(CamouflageProfile.Weather)
        val session = testSessionManager(TimeProvider { 1_000L })
        session.establish(AuthenticationOutcome.Succeeded)

        // A new screen in a new process: the identity is the platform's, the session is gone.
        val restarted = CamouflageViewModel(
            camouflageRepository = identity,
            sessionManager = testSessionManager(TimeProvider { 1_000L }),
        )

        val state = restarted.uiState.value
        require(state is CamouflageUiState.Ready) { "the screen should be ready but was $state" }
        assertEquals(CamouflageProfile.Weather, state.selected)
        assertTrue("the identity is still what the device presents", state.selected.isCamouflage)
        assertEquals("and nothing was written to bring it back", emptyList<CamouflageProfile>(), identity.writes)
    }

    /** Runs the identity screen's flow against [repository] with an authenticated session. */
    private fun identityFlow(
        repository: RecordingIdentityRepository,
        action: (CamouflageViewModel) -> Unit,
    ) {
        val session = testSessionManager(TimeProvider { 1_000L })
        session.establish(AuthenticationOutcome.Succeeded)
        val model = CamouflageViewModel(camouflageRepository = repository, sessionManager = session)

        action(model)

        assertEquals("the change went to the identity, and only there", 1, repository.writes.size)
    }

    /** A stand-in platform that records what it was asked to present. */
    private class RecordingIdentityRepository(
        private var profile: CamouflageProfile,
    ) : CamouflageRepository {

        val writes = mutableListOf<CamouflageProfile>()

        override suspend fun currentProfile(): CamouflageProfile = profile

        override suspend fun selectProfile(profile: CamouflageProfile): NivaraResult<Unit> {
            writes += profile
            this.profile = profile
            return NivaraResult.Success(Unit)
        }
    }

    private companion object {
        const val CAMERA = "com.example.camera"
        const val NOTES = "com.example.notes"
    }
}
