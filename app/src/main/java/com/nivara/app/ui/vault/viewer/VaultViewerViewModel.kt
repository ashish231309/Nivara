package com.nivara.app.ui.vault.viewer

import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.vault.viewer.VaultDocumentEngine
import com.nivara.app.data.vault.viewer.VaultDocumentEngineFactory
import com.nivara.app.data.vault.viewer.VaultImageEngine
import com.nivara.app.data.vault.viewer.VaultMediaEngine
import com.nivara.app.data.vault.viewer.VaultMediaEngineFactory
import com.nivara.app.data.vault.viewer.VaultMediaState
import com.nivara.app.data.vault.viewer.VaultPdfSession
import com.nivara.app.data.vault.viewer.viewerFailure
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.vault.VaultContentClassification
import com.nivara.app.ui.vault.VaultItemUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The viewer's state machine: one item, one session, one turn at a time.
 *
 * ### What it never does
 *
 * It never authenticates anything. It asks the existing [SessionManager] whether the gate is open,
 * and if it is not, the viewer is [VaultViewerUiState.Locked] and the screen sends the user to the
 * credential screen that already exists. It never calls `establish`, never refreshes a session
 * because media is playing, never extends a timeout and never locks anything: the gate belongs to the
 * application, and a viewer that could open or lengthen it would be a second gate.
 *
 * ### What ends a viewer
 *
 * The session. [SessionManager.state] is watched for as long as the viewer is alive, and the moment
 * it stops being authenticated — a timeout, or Quick Lock from anywhere in the application — the
 * engines are released and the state becomes [VaultViewerUiState.Locked]. The vault's own reader
 * enforces the same rule independently: it asks before every piece of content it serves, so a viewer
 * that somehow missed the transition would still stop receiving plaintext.
 *
 * ### Where the content lives
 *
 * Decoded content — a bitmap, a text preview, a rendered page, a playing session — is held by the
 * engines this view model owns, not by the state. The screen asks for it while the state says it is
 * there, and every way out of a viewer ([close], [onClosed], a locked session, [onCleared]) releases
 * it.
 */
internal class VaultViewerViewModel(
    private val mediaEngines: VaultMediaEngineFactory,
    private val imageEngine: VaultImageEngine,
    private val documentEngines: VaultDocumentEngineFactory,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<VaultViewerUiState>(VaultViewerUiState.Closed)

    /** What the viewer is doing. */
    val uiState: StateFlow<VaultViewerUiState> = mutableUiState.asStateFlow()

    private var mediaEngine: VaultMediaEngine? = null
    private var documentEngine: VaultDocumentEngine? = null

    /** The decoded image on screen, owned here and recycled when it leaves. */
    internal var image: Bitmap? = null
        private set

    /** The rendered document page on screen, owned here and recycled when it leaves. */
    internal var documentPage: Bitmap? = null
        private set

    /** The text preview on screen, bounded by the engine that read it. */
    internal var text: String? = null
        private set

    /** The open document session, when one is open. */
    internal var document: VaultPdfSession? = null
        private set

    /** The media engine of the open item, when one is playing. */
    internal val media: VaultMediaEngine?
        get() = mediaEngine

    /** The surface the screen is drawing video into, when there is one. */
    private var mediaSurface: Surface? = null

    private var openItem: VaultViewerItem? = null
    private var opening: Job? = null
    private var mediaObserver: Job? = null
    private var positionTicker: Job? = null

    init {
        // The gate is watched rather than asked once: a session that ends while content is open must
        // close it, whichever way it ended.
        viewModelScope.launch {
            sessionManager.state.collect { session ->
                if (!session.isAuthenticated && openItem != null && mutableUiState.value.isOpen) {
                    onSessionEnded()
                }
            }
        }
    }

    /**
     * Opens [item].
     *
     * A second open while one is running is refused rather than queued: two viewers over one vault
     * would hold two key borrows and two decoders for no purpose a person asked for.
     */
    fun open(item: VaultItemUi) {
        if (mutableUiState.value.isOpen) return
        val target = item.toViewerItem()
        releaseContent()
        openItem = target

        if (!sessionManager.currentState().isAuthenticated) {
            // The screen sends the user to the existing credential screen; the item is not opened and
            // nothing is read until the gate is open.
            mutableUiState.value = VaultViewerUiState.Locked(target)
            return
        }
        begin(target)
    }

    /** Retries the last item, used by states that can change: storage that was out of reach, or a
     * session that has been reopened since the item was locked. */
    fun onRetry() {
        val item = openItem ?: return
        if (mutableUiState.value.isOpen) return
        if (!sessionManager.currentState().isAuthenticated) {
            mutableUiState.value = VaultViewerUiState.Locked(item)
            return
        }
        begin(item)
    }

    /**
     * Called when the screen comes back to the foreground.
     *
     * A viewer that was locked because the gate had closed reopens once the gate is open again — the
     * user has authenticated through the existing credential screen, and the item they were looking
     * at is the one they asked for. Nothing here opens a session: it only notices that one exists.
     */
    fun onResumed() {
        if (mutableUiState.value is VaultViewerUiState.Locked && openItem != null) {
            onRetry()
        }
    }

    /** Closes the viewer: content is released and the screen goes back to the list. */
    fun close() {
        releaseContent()
        openItem = null
        mutableUiState.value = VaultViewerUiState.Closed
    }

    /** Called when the screen that hosted the viewer goes away. */
    fun onClosed() = close()

    /** The screen has a drawing surface for video. */
    fun onSurfaceAvailable(surface: Surface) {
        mediaSurface = surface
        mediaEngine?.attachSurface(surface)
    }

    /** The screen's drawing surface is gone; playback itself is untouched. */
    fun onSurfaceDestroyed() {
        mediaSurface = null
        mediaEngine?.detachSurface()
    }

    /** Called when the screen leaves the foreground: nothing plays in the background this stage. */
    fun onPaused() {
        if (mediaEngine?.state?.value is VaultMediaState.Playing) {
            mediaEngine?.pause()
        }
    }

    fun onPlay() {
        val engine = mediaEngine ?: return
        engine.play()
        startPositionTicker()
    }

    fun onPause() = mediaEngine?.pause()

    fun onSeekTo(positionMillis: Long) {
        mediaEngine?.seekTo(positionMillis)
    }

    /** Moves to the next page of the open document, when there is one. */
    fun onNextPage() {
        val state = mutableUiState.value as? VaultViewerUiState.Document ?: return
        if (state.page + 1 >= state.pageCount) return
        goToPage(state, index = state.page + 1)
    }

    /** Moves to the previous page of the open document, when there is one. */
    fun onPreviousPage() {
        val state = mutableUiState.value as? VaultViewerUiState.Document ?: return
        if (state.page <= 0) return
        goToPage(state, index = state.page - 1)
    }

    /** Turns to [index], drawing the page that was on screen until the new one is ready. */
    private fun goToPage(state: VaultViewerUiState.Document, index: Int) {
        mutableUiState.value = state.copy(page = index)
        renderDocumentPage(target = state.item, index = index)
    }

    // ------------------------------------------------------------------ opening

    private fun begin(target: VaultViewerItem) {
        // One open at a time, and a new one replaces any that was still running.
        opening?.cancel()
        mutableUiState.value = VaultViewerUiState.Opening(target)
        opening = viewModelScope.launch { openContent(target) }
    }

    /** Opens the content of [target] according to its type, publishing what came of it. */
    private suspend fun openContent(target: VaultViewerItem) {
        val mimeType = target.mimeType
        when {
            VaultContentClassification.isViewableImage(mimeType) -> openImage(target)
            VaultContentClassification.isPlayableVideo(mimeType) -> openMedia(target, video = true)
            VaultContentClassification.isPlayableAudio(mimeType) -> openMedia(target, video = false)
            VaultContentClassification.isReadableText(mimeType) -> openText(target)
            VaultContentClassification.isRenderableDocument(mimeType) -> openDocument(target)
            else -> mutableUiState.value = VaultViewerUiState.Unsupported(target)
        }
    }

    private suspend fun openImage(target: VaultViewerItem) {
        val decoded = imageEngine.decode(target.id, target.sizeBytes, authorize = ::authorized)
        // A viewer that closed or moved on while the read was suspended publishes nothing.
        if (openItem != target) return
        val image = decoded.valueOrNull()
        if (image == null) {
            mutableUiState.value = decoded.failureState(target)
            return
        }
        this.image = image.bitmap
        mutableUiState.value = VaultViewerUiState.Image(
            item = target,
            width = image.width,
            height = image.height,
            sampled = image.sampled,
        )
    }

    private suspend fun openMedia(target: VaultViewerItem, video: Boolean) {
        val engine = mediaEngines.create(viewModelScope)
        mediaEngine = engine
        val result = engine.open(target.id, target.sizeBytes, authorize = ::authorized)
        if (openItem != target) return
        if (result.valueOrNull() == null) {
            mutableUiState.value = result.failureState(target)
            return
        }
        // A surface that arrived before the engine did is attached now, so video that was started by
        // a tap does not wait for the screen to be laid out again.
        mediaSurface?.let { surface -> engine.attachSurface(surface) }
        observeMedia(engine = engine, target = target, video = video)
        publishMedia(target = target, video = video)
    }

    private suspend fun openText(target: VaultViewerItem) {
        val engine = documentEngines.create(viewModelScope)
        documentEngine = engine
        val preview = engine.readText(target.id, target.sizeBytes, authorize = ::authorized)
        if (openItem != target) return
        val read = preview.valueOrNull()
        if (read == null) {
            mutableUiState.value = preview.failureState(target)
            return
        }
        text = read.text
        mutableUiState.value = VaultViewerUiState.Text(
            item = target,
            characters = read.characters,
            truncated = read.truncated,
        )
    }

    private suspend fun openDocument(target: VaultViewerItem) {
        val engine = documentEngines.create(viewModelScope)
        documentEngine = engine
        val opened = engine.openDocument(target.id, target.sizeBytes, authorize = ::authorized)
        if (openItem != target) return
        val session = opened.valueOrNull()
        if (session == null) {
            mutableUiState.value = opened.failureState(target)
            return
        }
        document = session
        // The document is on screen as soon as it opens — its page count is known and the pages are
        // what a reader navigates — and the first page is drawn as it is rendered.
        mutableUiState.value = VaultViewerUiState.Document(
            item = target,
            page = 0,
            pageCount = session.pageCount,
        )
        renderDocumentPage(target = target, index = 0)
    }

    /**
     * Renders one page of the open document, replacing the page on screen.
     *
     * The state moves to the page that was asked for as soon as it is asked for, so the page label and
     * the arrows never disagree with what the reader pressed; the picture arrives when the renderer
     * has produced it, and a page the renderer refuses ends the document rather than leaving the
     * previous page on screen under the new page's number.
     */
    private fun renderDocumentPage(target: VaultViewerItem, index: Int) {
        val session = document ?: return
        viewModelScope.launch {
            val rendered = session.renderPage(index)
            // The viewer may have been closed, locked or pointed at another file while the page was
            // rendering; a page that arrives for a viewer that is gone is dropped, and the failure of
            // such a page must not reopen a closed viewer in a failed state.
            if (openItem != target) return@launch
            val page = rendered.valueOrNull()
            if (page == null) {
                mutableUiState.value = rendered.failureState(target)
                return@launch
            }
            recycleDocumentPage()
            documentPage = page.bitmap
        }
    }

    // ------------------------------------------------------------------ media

    /** Follows the engine's phases, and keeps the drawn position moving while it plays. */
    private fun observeMedia(engine: VaultMediaEngine, target: VaultViewerItem, video: Boolean) {
        mediaObserver?.cancel()
        mediaObserver = viewModelScope.launch {
            engine.state.collect { engineState ->
                // An engine from an earlier item may still be publishing; only the item on screen
                // may write to the state.
                if (openItem != target) return@collect
                if (engineState is VaultMediaState.Failed) {
                    mutableUiState.value = engineState.failure.toUiState(target)
                } else {
                    publishMedia(target = target, video = video)
                }
            }
        }
        ensurePositionTicker(engine = engine, target = target, video = video)
    }

    /** Draws the engine's current phase, or nothing when the viewer has moved on. */
    private fun publishMedia(target: VaultViewerItem, video: Boolean) {
        val engine = mediaEngine ?: return
        if (openItem != target) return
        val engineState = engine.state.value
        if (engineState is VaultMediaState.Failed) {
            mutableUiState.value = engineState.failure.toUiState(target)
            return
        }
        val playing = engineState is VaultMediaState.Playing
        mutableUiState.value = if (video) {
            VaultViewerUiState.Video(
                item = target,
                playing = playing,
                positionMillis = engine.positionMillis(),
                durationMillis = engine.durationMillis,
            )
        } else {
            VaultViewerUiState.Audio(
                item = target,
                playing = playing,
                positionMillis = engine.positionMillis(),
                durationMillis = engine.durationMillis,
            )
        }
    }

    /**
     * Keeps the drawn position moving while the item plays, and stops when it does not.
     *
     * The loop ends with playback rather than running for the life of the viewer: nothing wakes up
     * for a paused or finished file, and a ticker that ended is simply started again by the next play.
     */
    private fun ensurePositionTicker(
        engine: VaultMediaEngine,
        target: VaultViewerItem,
        video: Boolean,
    ) {
        if (positionTicker?.isActive == true) return
        positionTicker = viewModelScope.launch {
            while (isActive) {
                delay(POSITION_TICK_MILLIS)
                if (engine.state.value !is VaultMediaState.Playing) return@launch
                publishMedia(target = target, video = video)
            }
        }
    }

    /** Starts the position ticker for whatever is playing now, if anything is. */
    private fun startPositionTicker() {
        val engine = mediaEngine ?: return
        val target = openItem ?: return
        if (engine.state.value !is VaultMediaState.Playing) return
        ensurePositionTicker(
            engine = engine,
            target = target,
            video = mutableUiState.value is VaultViewerUiState.Video,
        )
    }

    // ------------------------------------------------------------------ endings

    /** The session ended while content was open: everything is released before the state says so. */
    private fun onSessionEnded() {
        val item = openItem
        releaseContent()
        mutableUiState.value = VaultViewerUiState.Locked(item)
    }

    /** Whether the existing gate currently authorizes reading a piece of content. */
    private fun authorized(): Boolean = sessionManager.currentState().isAuthenticated

    /** Releases every decoded thing this viewer owns. Safe to call repeatedly. */
    private fun releaseContent() {
        positionTicker?.cancel()
        positionTicker = null
        // An open that was still running is cancelled rather than left to publish content into a
        // viewer that has already closed.
        opening?.cancel()
        opening = null
        mediaObserver?.cancel()
        mediaObserver = null
        mediaEngine?.release()
        mediaEngine = null
        mediaSurface = null
        documentEngine?.release()
        documentEngine = null
        document = null
        text = null
        imageEngine.release()
        image = null
        recycleDocumentPage()
    }

    private fun recycleDocumentPage() {
        val page = documentPage ?: return
        documentPage = null
        if (!page.isRecycled) page.recycle()
    }

    override fun onCleared() {
        // The screen is gone for good: nothing may outlive the viewer, least of all a borrow of the
        // vault's key.
        releaseContent()
        super.onCleared()
    }

    companion object {

        /** How often the drawn position is refreshed while something plays. */
        private const val POSITION_TICK_MILLIS = 500L

        /** Factory that supplies the viewer with its engines from the application container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                VaultViewerViewModel(
                    mediaEngines = container.vaultMediaEngines,
                    imageEngine = container.vaultImageEngine,
                    documentEngines = container.vaultDocumentEngines,
                    sessionManager = container.sessionManager,
                )
            }
        }
    }
}

/**
 * The failure behind a viewer result, as the state that describes it.
 *
 * A result that failed without a typed viewer failure is reported as [VaultViewerUiState.Failed]
 * rather than ignored: a screen that showed an empty box because a result was unexpected would be the
 * one thing the vault never does.
 */
private fun <T> NivaraResult<T>.failureState(item: VaultViewerItem): VaultViewerUiState =
    viewerFailure()?.toUiState(item) ?: VaultViewerUiState.Failed(item)

/** The viewer's own view of an item: the facts it shows, and the identifier it opens. */
internal fun VaultItemUi.toViewerItem(): VaultViewerItem = VaultViewerItem(
    id = id,
    name = name,
    mimeType = mimeType,
    kind = VaultContentClassification.kindOf(mimeType),
    sizeBytes = sizeBytes,
    importedAtEpochMillis = importedAtEpochMillis,
)
