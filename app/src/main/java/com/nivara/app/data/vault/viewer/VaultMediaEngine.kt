package com.nivara.app.data.vault.viewer

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultContentHandle
import com.nivara.app.domain.vault.VaultContentReader
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a playing item is, for a screen that draws it.
 *
 * Nothing here is content: a state names a phase and, at most, a duration or a typed failure. The
 * bytes being played live in the media stack, behind the data source, and never enter this type.
 */
internal sealed interface VaultMediaState {

    /** Nothing is open. */
    data object Idle : VaultMediaState

    /** The player is reading the item's header, through the vault's decryption. */
    data object Preparing : VaultMediaState

    /** Ready to play, holding the decrypted stream. */
    data class Ready(val durationMillis: Long) : VaultMediaState

    data object Playing : VaultMediaState

    data object Paused : VaultMediaState

    data object Completed : VaultMediaState

    /** The player refused the content, or the vault stopped serving it. */
    data class Failed(val failure: VaultViewerFailure) : VaultMediaState
}

/**
 * Plays one vault item — audio or video, the platform decides what it can decode — without ever
 * seeing the vault's key.
 *
 * ### Ownership
 *
 * An engine is created for one viewer and released when that viewer closes. Opening an item runs
 * inside one borrow of the vault key, held by the coroutine that performs it; [stop] or [release]
 * ends that coroutine, which closes the content and clears the key. Nothing is left running when the
 * screen goes away, and nothing keeps a decrypted stream alive after the session ends — the content
 * handle itself refuses to serve a byte once the gate is closed.
 *
 * ### Video
 *
 * A video surface belongs to the screen, so it is attached to the running session ([attachSurface])
 * rather than held by the engine. Detaching does not stop playback: leaving a screen pauses it,
 * closing the viewer ends it.
 */
internal interface VaultMediaEngine {

    /** Current phase. */
    val state: StateFlow<VaultMediaState>

    /** The prepared item's duration in milliseconds, or 0 when nothing is prepared. */
    val durationMillis: Long

    /** The playing position in milliseconds. */
    fun positionMillis(): Long

    /**
     * Opens [itemId] and prepares it for playback.
     *
     * @param sizeBytes the plaintext size the index recorded, handed to the media source as the size
     *   it must declare before the platform reads the first byte.
     * @param authorize asked before the item is opened and before every later piece; a session that
     *   has ended stops playback rather than being extended by it.
     */
    suspend fun open(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultMediaState.Ready>

    /** Draws video into [surface]. */
    fun attachSurface(surface: Surface)

    /** Stops drawing into the surface. Playback itself is unaffected. */
    fun detachSurface()

    fun play()

    fun pause()

    fun seekTo(positionMillis: Long)

    /** Ends the session: the player is released, the content closed and the key borrow finished. */
    fun stop()

    /** Ends the session if it is still running. Safe to call more than once. */
    fun release()
}

/**
 * The platform's player over the vault's decrypted content.
 *
 * One `MediaPlayer`, one [VaultMediaDataSource], one borrow of the vault key — for the lifetime of
 * one playback session and no longer. The player is created on the caller's dispatcher (the viewer's
 * main thread), so its callbacks arrive there and no lock is held across a platform call.
 */
internal class NivaraVaultMediaEngine(
    private val reader: VaultContentReader,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val playerFactory: () -> MediaPlayer = { MediaPlayer() },
) : VaultMediaEngine {

    private val mutableState = MutableStateFlow<VaultMediaState>(VaultMediaState.Idle)

    override val state: StateFlow<VaultMediaState> = mutableState.asStateFlow()

    override val durationMillis: Long
        get() = session?.durationMillis() ?: 0L

    private var session: Session? = null
    private var borrow: Job? = null

    override suspend fun open(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultMediaState.Ready> {
        release()
        mutableState.value = VaultMediaState.Preparing

        val ready = CompletableDeferred<NivaraResult<VaultMediaState.Ready>>()
        borrow = scope.launch {
            val outcome = reader.withContent(itemId, sizeBytes, authorize) { handle ->
                val opened = openSession(handle = handle, sizeBytes = sizeBytes, authorize = authorize)
                if (opened != null) {
                    ready.complete(NivaraResult.Success(opened))
                    // The block stays until the session ends: this call is what keeps the key
                    // borrowed exactly as long as the viewer is playing.
                    session?.awaitFinished()
                }
                NivaraResult.Success(Unit)
            }
            if (!ready.isCompleted) {
                // Either the vault refused the read, or the player refused the content — in which
                // case the state already says so — and in either case nothing is left open.
                val failure = outcome.viewerFailure()
                    ?: (mutableState.value as? VaultMediaState.Failed)?.failure
                    ?: VaultViewerFailure.Content(VaultContentFailure.Unreadable)
                mutableState.value = VaultMediaState.Failed(failure)
                ready.complete(viewerFailure(failure))
            }
        }
        // A release that lands while the item is still opening ends the attempt rather than leaving
        // the caller suspended on a borrow that is already gone.
        borrow?.invokeOnCompletion { cause ->
            if (!ready.isCompleted) {
                val failure = if (cause is CancellationException) {
                    VaultViewerFailure.Cancelled
                } else {
                    VaultViewerFailure.Content(VaultContentFailure.Unreadable)
                }
                ready.complete(viewerFailure(failure))
            }
        }
        return ready.await()
    }

    /**
     * Creates the player for [handle] and waits for it to be prepared.
     *
     * Returns the ready state, or `null` when the player refused the content — in which case the
     * failure has already been published and the session released.
     */
    private suspend fun openSession(
        handle: VaultContentHandle,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): VaultMediaState.Ready? = withContext(dispatcher) {
        val player = playerFactory()
        val source = VaultMediaDataSource(handle = handle, declaredSizeBytes = sizeBytes)
        val prepared = CompletableDeferred<Unit>()
        val created = Session(player = player, source = source)

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        player.setOnPreparedListener { prepared.complete(Unit) }
        player.setOnCompletionListener { mutableState.value = VaultMediaState.Completed }
        player.setOnErrorListener { _, _, _ ->
            // The platform refused the content: a codec it does not have, or bytes it could not
            // parse. The viewer says the file could not be played, and never calls the vault damaged.
            prepared.completeExceptionally(IOException("the media player refused the content"))
            true
        }

        try {
            player.setDataSource(source)
            player.prepareAsync()
        } catch (refused: Exception) {
            prepared.completeExceptionally(refused)
        }

        val outcome = awaitPrepared(prepared)
        if (outcome != null) {
            created.releasePlayer()
            mutableState.value = VaultMediaState.Failed(outcome)
            return@withContext null
        }

        session = created
        val ready = VaultMediaState.Ready(durationMillis = created.durationMillis())
        mutableState.value = ready
        ready
    }

    /** Waits for the player to be ready, turning a refusal into the failure the screen shows. */
    private suspend fun awaitPrepared(prepared: CompletableDeferred<Unit>): VaultViewerFailure? =
        try {
            val preparedInTime = withTimeoutOrNull(PREPARE_TIMEOUT_MILLIS) {
                prepared.await()
                true
            } ?: false
            if (preparedInTime) null else VaultViewerFailure.DecodeFailed
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: Exception) {
            VaultViewerFailure.DecodeFailed
        }

    override fun attachSurface(surface: Surface) {
        session?.attachSurface(surface)
    }

    override fun detachSurface() {
        session?.detachSurface()
    }

    override fun play() {
        val playing = session ?: return
        if (playing.play()) {
            mutableState.value = VaultMediaState.Playing
        }
    }

    override fun pause() {
        val paused = session ?: return
        if (paused.pause()) {
            mutableState.value = VaultMediaState.Paused
        }
    }

    override fun seekTo(positionMillis: Long) {
        session?.seekTo(positionMillis)
    }

    override fun positionMillis(): Long = session?.positionMillis() ?: 0L

    override fun stop() = release()

    override fun release() {
        session?.releasePlayer()
        session = null
        borrow?.cancel()
        borrow = null
        if (mutableState.value !is VaultMediaState.Failed) {
            mutableState.value = VaultMediaState.Idle
        }
    }

    /**
     * One prepared playback: the player, the data source over the decrypted stream, and the signal
     * that ends the borrow.
     */
    private inner class Session(
        private val player: MediaPlayer,
        private val source: VaultMediaDataSource,
    ) {

        private val finished = CompletableDeferred<Unit>()

        /** Suspends until the viewer stops or releases this session. */
        suspend fun awaitFinished() {
            finished.await()
        }

        fun durationMillis(): Long = runCatching { player.duration.toLong() }.getOrDefault(0L)

        fun positionMillis(): Long = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)

        fun attachSurface(surface: Surface) {
            runCatching { player.setSurface(surface) }
        }

        fun detachSurface() {
            runCatching { player.setSurface(null) }
        }

        fun play(): Boolean = runCatching {
            player.start()
            true
        }.getOrDefault(false)

        fun pause(): Boolean = runCatching {
            player.pause()
            true
        }.getOrDefault(false)

        fun seekTo(positionMillis: Long) {
            runCatching { player.seekTo(positionMillis.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST_SYNC) }
        }

        /** Releases the player once; the second call does nothing. */
        fun releasePlayer() {
            if (finished.isCompleted) return
            runCatching { player.setSurface(null) }
            runCatching { player.stop() }
            runCatching { player.release() }
            runCatching { source.close() }
            finished.complete(Unit)
        }
    }

    private companion object {

        /**
         * How long the platform may take to read a header before the viewer says it could not play
         * the file. Generous, because the header may be a decrypt-and-seek away on a slow device.
         */
        const val PREPARE_TIMEOUT_MILLIS = 20_000L
    }
}
