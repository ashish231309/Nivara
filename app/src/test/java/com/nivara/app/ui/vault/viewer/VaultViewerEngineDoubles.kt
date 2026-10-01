package com.nivara.app.ui.vault.viewer

import android.view.Surface
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.viewer.VaultDecodedImage
import com.nivara.app.data.vault.viewer.VaultDocumentEngine
import com.nivara.app.data.vault.viewer.VaultDocumentEngineFactory
import com.nivara.app.data.vault.viewer.VaultImageEngine
import com.nivara.app.data.vault.viewer.VaultMediaEngine
import com.nivara.app.data.vault.viewer.VaultMediaEngineFactory
import com.nivara.app.data.vault.viewer.VaultMediaState
import com.nivara.app.data.vault.viewer.VaultPdfSession
import com.nivara.app.data.vault.viewer.VaultTextPreview
import com.nivara.app.data.vault.viewer.VaultViewerFailure
import com.nivara.app.data.vault.viewer.viewerFailure
import com.nivara.app.domain.vault.VaultItemId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recording doubles for the viewer's engines.
 *
 * The engines themselves are platform adapters — a media player, an image decoder, a document
 * renderer — so what a JVM suite can verify is the viewer's half of the contract: which engine is
 * asked for which item, what it is asked to do, and whether it is released when the screen ends. Each
 * double records exactly that, and each can be told to answer with any failure the vault can produce,
 * which is how "a corrupt file does not look like a missing one" is checked without a device.
 */

/** A media engine that records everything and plays nothing. */
internal class RecordingMediaEngine : VaultMediaEngine {

    private val mutableState = MutableStateFlow<VaultMediaState>(VaultMediaState.Idle)

    override val state: StateFlow<VaultMediaState> = mutableState.asStateFlow()

    /** What [open] answers with. `null` means a ready file. */
    var openFailure: VaultViewerFailure? = null

    /** When set, [open] waits on it before returning, so a test can hold an open in progress. */
    var openGate: CompletableDeferred<Unit>? = null

    var durationMillisValue: Long = 12_000L

    var openCalls: Int = 0
        private set
    var playCalls: Int = 0
        private set
    var pauseCalls: Int = 0
        private set
    var seekCalls: Int = 0
        private set
    var releaseCalls: Int = 0
        private set
    var attachCalls: Int = 0
        private set
    var detachCalls: Int = 0
        private set
    var lastItemId: VaultItemId? = null
        private set
    var lastSizeBytes: Long = 0L
        private set
    var lastSeekMillis: Long = -1L
        private set

    override val durationMillis: Long get() = durationMillisValue

    override fun positionMillis(): Long = 0L

    override suspend fun open(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultMediaState.Ready> {
        openCalls += 1
        lastItemId = itemId
        lastSizeBytes = sizeBytes
        openGate?.await()
        openFailure?.let { failure ->
            mutableState.value = VaultMediaState.Failed(failure)
            return viewerFailure(failure)
        }
        val ready = VaultMediaState.Ready(durationMillis = durationMillisValue)
        mutableState.value = ready
        return NivaraResult.Success(ready)
    }

    override fun attachSurface(surface: Surface) {
        attachCalls += 1
    }

    override fun detachSurface() {
        detachCalls += 1
    }

    override fun play() {
        playCalls += 1
        mutableState.value = VaultMediaState.Playing
    }

    override fun pause() {
        pauseCalls += 1
        mutableState.value = VaultMediaState.Paused
    }

    override fun seekTo(positionMillis: Long) {
        seekCalls += 1
        lastSeekMillis = positionMillis
    }

    override fun stop() = release()

    override fun release() {
        releaseCalls += 1
        mutableState.value = VaultMediaState.Idle
    }
}

/** Hands the suite's media engine to the viewer, and counts how often one was asked for. */
internal class RecordingMediaEngineFactory(
    val engine: RecordingMediaEngine = RecordingMediaEngine(),
) : VaultMediaEngineFactory {

    var creates: Int = 0
        private set

    override fun create(scope: CoroutineScope): VaultMediaEngine {
        creates += 1
        return engine
    }
}

/** An image engine that decodes nothing: it answers with a failure the test chose. */
internal class RecordingImageEngine : VaultImageEngine {

    var failure: VaultViewerFailure = VaultViewerFailure.DecodeFailed

    var decodeCalls: Int = 0
        private set
    var releaseCalls: Int = 0
        private set
    var lastItemId: VaultItemId? = null
        private set
    var lastSizeBytes: Long = 0L
        private set

    override suspend fun decode(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultDecodedImage> {
        decodeCalls += 1
        lastItemId = itemId
        lastSizeBytes = sizeBytes
        return viewerFailure(failure)
    }

    override fun release() {
        releaseCalls += 1
    }
}

/** A document engine that returns a chosen text preview, or a chosen failure. */
internal class RecordingDocumentEngine : VaultDocumentEngine {

    var textFailure: VaultViewerFailure? = null
    var textPreview: VaultTextPreview = VaultTextPreview(
        text = "the beginning of a document",
        characters = 29,
        truncated = false,
    )

    var openFailure: VaultViewerFailure? = null
    var pageCount: Int = 3

    /** Pages the session was asked to render, in order. */
    val renderedPages: MutableList<Int> = mutableListOf()

    var releaseCalls: Int = 0
        private set

    override suspend fun readText(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultTextPreview> {
        textFailure?.let { failure -> return viewerFailure(failure) }
        return NivaraResult.Success(textPreview)
    }

    override suspend fun openDocument(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultPdfSession> {
        openFailure?.let { failure -> return viewerFailure(failure) }
        return NivaraResult.Success(RecordingPdfSession(engine = this))
    }

    override fun release() {
        releaseCalls += 1
    }
}

/** A document session that records the pages it was asked for. */
internal class RecordingPdfSession(private val engine: RecordingDocumentEngine) : VaultPdfSession {

    override val pageCount: Int get() = engine.pageCount

    override suspend fun renderPage(
        index: Int,
        maximumDimension: Int,
    ): NivaraResult<VaultDecodedImage> {
        engine.renderedPages += index
        // Rendering needs a real bitmap, which only a device has; what the JVM suites need is the
        // failure path and the fact that a page was asked for.
        return viewerFailure(VaultViewerFailure.DecodeFailed)
    }

    override fun release() = Unit
}

/** Hands the suite's document engine to the viewer. */
internal class RecordingDocumentEngineFactory(
    val engine: RecordingDocumentEngine = RecordingDocumentEngine(),
) : VaultDocumentEngineFactory {

    var creates: Int = 0
        private set

    override fun create(scope: CoroutineScope): VaultDocumentEngine {
        creates += 1
        return engine
    }
}
