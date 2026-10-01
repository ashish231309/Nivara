package com.nivara.app.ui.vault.viewer

import com.nivara.app.data.vault.viewer.VaultTextPreview
import com.nivara.app.data.vault.viewer.VaultViewerFailure
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.TEST_SESSION_TIMEOUT_MILLIS
import com.nivara.app.testing.testSessionManager
import com.nivara.app.ui.vault.VaultItemUi
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
 * Local JVM tests for the viewer's state machine, its ownership of decoded content and its relationship
 * with the session gate.
 *
 * The platform decoders cannot run here — a bitmap needs a device — so the engines are the recording
 * doubles next to this file. What that leaves is exactly what the viewer owns: which engine is asked
 * for which item, what a failure is called on screen, what happens to an engine that is open when the
 * screen closes or the session ends, and the fact that the viewer never touches the gate itself.
 *
 * The session is the real [com.nivara.app.data.session.InMemorySessionManager] behind a recording
 * wrapper, so "the viewer never establishes, refreshes or locks a session" is a counted fact rather
 * than a claim read off the source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewerViewModelTest {

    private val random = SecureRandomGenerator()
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val time = MutableTimeProvider()
    private val counting = CountingSessionManager(testSessionManager(time))
    private val mediaFactory = RecordingMediaEngineFactory()
    private val imageEngine = RecordingImageEngine()
    private val documentFactory = RecordingDocumentEngineFactory()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // The vault's own screens only read when the gate is open, so that is where every test starts
        // from. The tests about the gate close it deliberately, through that gate's own lock.
        counting.delegate.establish(AuthenticationOutcome.Succeeded)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): VaultViewerViewModel = VaultViewerViewModel(
        mediaEngines = mediaFactory,
        imageEngine = imageEngine,
        documentEngines = documentFactory,
        sessionManager = counting,
    )

    private fun item(
        name: String = "holiday.jpg",
        mimeType: String? = "image/jpeg",
        kind: VaultContentKind = VaultContentKind.Image,
        sizeBytes: Long = 4_096L,
    ): VaultItemUi = VaultItemUi(
        id = VaultItemId.create(random),
        name = name,
        kind = kind,
        sizeBytes = sizeBytes,
        importedAtEpochMillis = 1_700_000_000_000L,
        mimeType = mimeType,
    )

    private fun video() = item(name = "clip.mp4", mimeType = "video/mp4", kind = VaultContentKind.Video)

    private fun audio() = item(name = "song.mp3", mimeType = "audio/mpeg", kind = VaultContentKind.Audio)

    private fun text() = item(name = "notes.txt", mimeType = "text/plain", kind = VaultContentKind.Document)

    private fun pdf() = item(name = "paper.pdf", mimeType = "application/pdf", kind = VaultContentKind.Document)

    private fun openGate() = CompletableDeferred<Unit>()

    // ------------------------------------------------------------------ what gets opened

    @Test
    fun `a file this stage has no viewer for is described, not opened`() {
        val viewer = viewModel()
        val unsupported = item(
            name = "archive.zip",
            mimeType = "application/octet-stream",
            kind = VaultContentKind.Other,
        )

        viewer.open(unsupported)

        val state = viewer.uiState.value
        assertTrue("the item is described rather than decoded", state is VaultViewerUiState.Unsupported)
        assertEquals(unsupported.id, state.item?.id)
        assertEquals("no decoder was involved", 0, mediaFactory.creates)
        assertEquals(0, imageEngine.decodeCalls)
        assertEquals(0, documentFactory.creates)
    }

    @Test
    fun `an image is opened through the image engine with its identifier and size`() {
        val viewer = viewModel()
        imageEngine.failure = VaultViewerFailure.Content(VaultContentFailure.ContentMissing)
        val picture = item(sizeBytes = 9_999L)

        viewer.open(picture)

        assertEquals(1, imageEngine.decodeCalls)
        assertEquals(picture.id, imageEngine.lastItemId)
        assertEquals(picture.sizeBytes, imageEngine.lastSizeBytes)
    }

    @Test
    fun `a picture the decoder refuses is a failure of the file, not of the vault`() {
        val viewer = viewModel()
        imageEngine.failure = VaultViewerFailure.DecodeFailed

        viewer.open(item())

        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Failed)
        assertFalse("a decode failure is not corruption", state is VaultViewerUiState.Corrupt)
    }

    @Test
    fun `a video is prepared on the platform engine and shown as not playing`() {
        val viewer = viewModel()
        val clip = video()

        viewer.open(clip)

        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Video)
        val prepared = state as VaultViewerUiState.Video
        assertFalse(prepared.playing)
        assertEquals(12_000L, prepared.durationMillis)
        assertEquals(clip.id, prepared.item.id)
        assertEquals(1, mediaFactory.creates)
        assertEquals(clip.id, mediaFactory.engine.lastItemId)
    }

    @Test
    fun `audio is prepared the same way and draws as audio`() {
        val viewer = viewModel()

        viewer.open(audio())

        assertTrue(viewer.uiState.value is VaultViewerUiState.Audio)
        assertEquals(1, mediaFactory.creates)
    }

    @Test
    fun `a text file is shown from a bounded preview`() {
        val viewer = viewModel()
        documentFactory.engine.textPreview = VaultTextPreview(
            text = "the first part of a long note",
            characters = 29,
            truncated = true,
        )

        viewer.open(text())

        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Text)
        val preview = state as VaultViewerUiState.Text
        assertEquals(29, preview.characters)
        assertTrue("the screen is told the file was longer than the preview", preview.truncated)
        assertEquals("the preview is the engine's, not the file's", "the first part of a long note", viewer.text)
        assertEquals(1, documentFactory.creates)
    }

    @Test
    fun `a document opens at its first page`() {
        val viewer = viewModel()

        viewer.open(pdf())

        assertEquals(1, documentFactory.creates)
        assertEquals("the first page is the one drawn", listOf(0), documentFactory.engine.renderedPages)
    }

    @Test
    fun `a page the renderer refuses ends the document rather than showing a stale page`() {
        val viewer = viewModel()

        viewer.open(pdf())

        // The recording session cannot draw — a bitmap needs a device — so what this covers is the
        // path a device takes when the platform's renderer refuses a page.
        assertTrue(viewer.uiState.value is VaultViewerUiState.Failed)
    }

    @Test
    fun `turning a page without a document does nothing`() {
        val viewer = viewModel()

        viewer.onNextPage()
        viewer.onPreviousPage()

        assertTrue(viewer.uiState.value is VaultViewerUiState.Closed)
        assertEquals(emptyList<Int>(), documentFactory.engine.renderedPages)
    }

    // ------------------------------------------------------------------ controls

    @Test
    fun `playing is passed to the engine and the drawn state follows it`() {
        val viewer = viewModel()
        viewer.open(video())

        viewer.onPlay()

        assertEquals(1, mediaFactory.engine.playCalls)
        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Video)
        assertTrue((state as VaultViewerUiState.Video).playing)
    }

    @Test
    fun `pausing is passed to the engine`() {
        val viewer = viewModel()
        viewer.open(video())
        viewer.onPlay()

        viewer.onPause()

        assertEquals(1, mediaFactory.engine.pauseCalls)
        assertFalse((viewer.uiState.value as VaultViewerUiState.Video).playing)
    }

    @Test
    fun `seeking is passed through with the position`() {
        val viewer = viewModel()
        viewer.open(video())

        viewer.onSeekTo(4_000L)

        assertEquals(1, mediaFactory.engine.seekCalls)
        assertEquals(4_000L, mediaFactory.engine.lastSeekMillis)
    }

    @Test
    fun `leaving the screen pauses playback`() {
        val viewer = viewModel()
        viewer.open(video())
        viewer.onPlay()

        viewer.onPaused()

        assertEquals("nothing plays in the background", 1, mediaFactory.engine.pauseCalls)
        assertFalse((viewer.uiState.value as VaultViewerUiState.Video).playing)
    }

    // ------------------------------------------------------------------ closing

    @Test
    fun `closing releases the engine and goes back to the list`() {
        val viewer = viewModel()
        viewer.open(video())

        viewer.close()

        assertTrue(viewer.uiState.value is VaultViewerUiState.Closed)
        assertEquals(1, mediaFactory.engine.releaseCalls)
        assertNull("no engine outlives the viewer", viewer.media)
        assertNull(viewer.text)
        assertNull(viewer.document)
    }

    @Test
    fun `closing twice is safe and releases once`() {
        val viewer = viewModel()
        viewer.open(audio())

        viewer.close()
        viewer.close()

        assertTrue(viewer.uiState.value is VaultViewerUiState.Closed)
        assertEquals("the engine was released when the viewer closed, not again afterwards", 1, mediaFactory.engine.releaseCalls)
    }

    @Test
    fun `the screen going away releases everything too`() {
        val viewer = viewModel()
        viewer.open(text())

        viewer.onClosed()

        assertTrue(viewer.uiState.value is VaultViewerUiState.Closed)
        assertEquals(1, documentFactory.engine.releaseCalls)
        assertNull(viewer.text)
    }

    @Test
    fun `an open that is cancelled by closing never publishes content`() {
        val viewer = viewModel()
        val gate = openGate()
        mediaFactory.engine.openGate = gate
        viewer.open(video())
        assertTrue(viewer.uiState.value is VaultViewerUiState.Opening)

        viewer.close()
        gate.complete(Unit)

        // The open was cancelled rather than left to finish into a viewer that is gone.
        assertTrue(viewer.uiState.value is VaultViewerUiState.Closed)
        assertEquals(1, mediaFactory.engine.releaseCalls)
    }

    @Test
    fun `the same item can be opened again after it is closed`() {
        val viewer = viewModel()
        val clip = video()

        viewer.open(clip)
        viewer.close()
        viewer.open(clip)

        assertEquals(2, mediaFactory.creates)
        assertTrue(viewer.uiState.value is VaultViewerUiState.Video)
    }

    @Test
    fun `a second open while one is running is refused`() {
        val viewer = viewModel()
        val gate = openGate()
        mediaFactory.engine.openGate = gate
        val first = video()
        viewer.open(first)

        viewer.open(audio())

        // Only the first item was ever handed to an engine, and the viewer still says it is opening it.
        assertEquals(1, mediaFactory.creates)
        assertEquals(first.id, (viewer.uiState.value as VaultViewerUiState.Opening).item.id)
        gate.complete(Unit)
    }

    // ------------------------------------------------------------------ failures, kept apart

    @Test
    fun `a missing object is shown as missing`() {
        val viewer = viewModel()
        mediaFactory.engine.openFailure = VaultViewerFailure.Content(VaultContentFailure.ContentMissing)

        viewer.open(video())

        assertTrue(viewer.uiState.value is VaultViewerUiState.Missing)
    }

    @Test
    fun `a corrupt object is shown as corrupt and never as a shorter file`() {
        val viewer = viewModel()
        mediaFactory.engine.openFailure = VaultViewerFailure.Content(VaultContentFailure.Corrupt)

        viewer.open(video())

        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Corrupt)
        assertFalse("damaged bytes are not a missing file", state is VaultViewerUiState.Missing)
    }

    @Test
    fun `storage that cannot be reached is unreadable`() {
        val viewer = viewModel()
        mediaFactory.engine.openFailure = VaultViewerFailure.Content(VaultContentFailure.Unreadable)

        viewer.open(audio())

        assertTrue(viewer.uiState.value is VaultViewerUiState.Unreadable)
    }

    @Test
    fun `a key that cannot be borrowed is unreadable rather than a damaged file`() {
        val viewer = viewModel()
        mediaFactory.engine.openFailure = VaultViewerFailure.Content(VaultContentFailure.KeyUnavailable)

        viewer.open(audio())

        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Unreadable)
        assertFalse("an unreachable key is not corruption", state is VaultViewerUiState.Corrupt)
    }

    // ------------------------------------------------------------------ the gate

    @Test
    fun `nothing is read when the gate is closed`() {
        counting.delegate.lockNow()
        val viewer = viewModel()
        val clip = video()

        viewer.open(clip)

        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Locked)
        assertEquals(clip.id, (state as VaultViewerUiState.Locked).item?.id)
        assertEquals("no engine is even made for a locked viewer", 0, mediaFactory.creates)
        assertEquals(0, imageEngine.decodeCalls)
        assertEquals(0, documentFactory.creates)
    }

    @Test
    fun `a failed authentication does not open the item`() {
        counting.delegate.lockNow()
        val viewer = viewModel()
        counting.delegate.establish(AuthenticationOutcome.Failed(blockedForMillis = 0L))

        viewer.open(video())

        assertTrue(viewer.uiState.value is VaultViewerUiState.Locked)
        assertEquals(0, mediaFactory.creates)
    }

    @Test
    fun `an item locked at open is opened again once the existing gate is open`() {
        counting.delegate.lockNow()
        val viewer = viewModel()
        val clip = video()
        viewer.open(clip)
        assertTrue(viewer.uiState.value is VaultViewerUiState.Locked)

        // The user authenticates on the existing credential screen; the viewer only notices.
        counting.delegate.establish(AuthenticationOutcome.Succeeded)
        viewer.onRetry()

        assertEquals(1, mediaFactory.creates)
        assertEquals(clip.id, (viewer.uiState.value as VaultViewerUiState.Video).item.id)
    }

    @Test
    fun `coming back to the screen reopens an item that was locked`() {
        counting.delegate.lockNow()
        val viewer = viewModel()
        viewer.open(video())

        counting.delegate.establish(AuthenticationOutcome.Succeeded)
        viewer.onResumed()

        assertTrue(viewer.uiState.value is VaultViewerUiState.Video)
    }

    @Test
    fun `a session that ends while a video is open releases it and shows the lock`() {
        val viewer = viewModel()
        counting.delegate.establish(AuthenticationOutcome.Succeeded)
        val clip = video()
        viewer.open(clip)
        viewer.onPlay()

        counting.delegate.lockNow()

        val state = viewer.uiState.value
        assertTrue(state is VaultViewerUiState.Locked)
        assertEquals(clip.id, (state as VaultViewerUiState.Locked).item?.id)
        assertEquals("playback was released, not left running", 1, mediaFactory.engine.releaseCalls)
        assertNull(viewer.media)
    }

    @Test
    fun `a session that ends while a text preview is open releases the document engine`() {
        val viewer = viewModel()
        counting.delegate.establish(AuthenticationOutcome.Succeeded)
        viewer.open(text())

        counting.delegate.lockNow()

        assertTrue(viewer.uiState.value is VaultViewerUiState.Locked)
        assertEquals(1, documentFactory.engine.releaseCalls)
        assertNull("the text does not outlive the session", viewer.text)
    }

    @Test
    fun `a session that has run out of time is treated as ended`() {
        val viewer = viewModel()
        counting.delegate.establish(AuthenticationOutcome.Succeeded)
        viewer.open(video())

        // The deadline passes; reading the gate at the application boundary applies it, and the gate
        // publishes the end itself.
        time.advanceBy(TEST_SESSION_TIMEOUT_MILLIS + 1)
        counting.delegate.currentState()

        assertTrue(viewer.uiState.value is VaultViewerUiState.Locked)
        assertEquals(1, mediaFactory.engine.releaseCalls)
    }

    @Test
    fun `the viewer never establishes, refreshes or locks a session itself`() {
        val viewer = viewModel()
        counting.delegate.establish(AuthenticationOutcome.Succeeded)
        viewer.open(video())
        viewer.onPlay()
        viewer.onPause()
        viewer.onSeekTo(1_000L)
        viewer.onPaused()
        viewer.close()
        viewer.open(audio())
        viewer.onRetry()
        viewer.onResumed()
        viewer.onClosed()

        // Whatever the screen did, the gate was only ever read by the viewer.
        assertEquals(0, counting.establishes)
        assertEquals(0, counting.locks)
        // And the one lock in this test was the application's, which the viewer responded to above.
        counting.delegate.lockNow()
        assertEquals(1, counting.locks)
    }

    /** The real gate, wrapped so a test can count who changed it. */
    private class CountingSessionManager(val delegate: SessionManager) : SessionManager {

        var establishes: Int = 0
            private set
        var locks: Int = 0
            private set

        override val state: StateFlow<SessionState> get() = delegate.state

        override fun establish(outcome: AuthenticationOutcome): SessionState {
            establishes += 1
            return delegate.establish(outcome)
        }

        override fun establish(
            outcome: com.nivara.app.domain.security.BiometricAuthenticationOutcome,
        ): SessionState {
            establishes += 1
            return delegate.establish(outcome)
        }

        override fun currentState(): SessionState = delegate.currentState()

        override fun lockNow(): SessionState {
            locks += 1
            return delegate.lockNow()
        }
    }
}
