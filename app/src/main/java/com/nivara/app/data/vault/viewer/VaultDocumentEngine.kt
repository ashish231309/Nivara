package com.nivara.app.data.vault.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.BoundedCollector
import com.nivara.app.domain.vault.VaultContentException
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultContentHandle
import com.nivara.app.domain.vault.VaultContentReader
import com.nivara.app.domain.vault.VaultItemId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Text read from a document, bounded, with the fact that it was longer than what is shown. */
internal data class VaultTextPreview(
    val text: String,
    val characters: Int,
    val truncated: Boolean,
)

/**
 * A document open for rendering.
 *
 * The same shape as the media session and for the same reason: a document is rendered page by page
 * while the vault's key is borrowed, so the session *is* the borrow. Releasing it ends the read.
 */
internal interface VaultPdfSession {

    /** Pages the document declares. */
    val pageCount: Int

    /** Renders one page within [maximumDimension] pixels, or reports why it could not be drawn. */
    suspend fun renderPage(
        index: Int,
        maximumDimension: Int = VaultImageBounded.MAXIMUM_DIMENSION,
    ): NivaraResult<VaultDecodedImage>

    /** Releases the renderer and ends the read. Safe to call more than once. */
    fun release()
}

/**
 * Reads documents: text as bounded text, PDF as rendered pages.
 *
 * ### Text
 *
 * A text document is read through the vault's content handle into a buffer bounded by
 * [MAXIMUM_TEXT_BYTES]. The whole object is still *decrypted* — which is what makes a damaged one
 * fail instead of showing a prefix — while only the bounded part is kept, and the caller is told when
 * the file was longer than what it shows.
 *
 * ### PDF
 *
 * The platform's `PdfRenderer` cannot read a stream: it needs a seekable file descriptor. Nivara
 * therefore hands it a **proxy file descriptor** — the platform's own mechanism for exposing a file
 * descriptor whose contents the application produces on demand — over the same content handle. There
 * is no plaintext file anywhere: the descriptor is a kernel object that exists for as long as the
 * viewer, is served from the decrypted stream in bounded pieces, and disappears when the session is
 * released. That is this stage's answer to "a platform API needs seekable access": the safe mechanism
 * the platform provides, rather than a temporary decrypted copy.
 *
 * A page is rendered into a bitmap scaled to the viewer's bound, so a page of any size costs a bounded
 * amount of memory. A document the renderer refuses is [VaultViewerFailure.DecodeFailed].
 */
internal interface VaultDocumentEngine {

    /** Reads a text document, bounded. */
    suspend fun readText(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultTextPreview>

    /** Opens a PDF for page rendering. */
    suspend fun openDocument(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultPdfSession>

    /** Releases the open document, if any. Safe to call more than once. */
    fun release()
}

/** The platform's reader over the vault's decrypted content. */
internal class NivaraVaultDocumentEngine(
    private val context: Context,
    private val reader: VaultContentReader,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VaultDocumentEngine {

    private var session: PdfSession? = null
    private var borrow: Job? = null

    override suspend fun readText(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultTextPreview> =
        reader.withContent(itemId, sizeBytes, authorize) { handle ->
            val collector = BoundedCollector(MAXIMUM_TEXT_BYTES)
            val chunk = ByteArray(READ_CHUNK_BYTES)
            while (true) {
                val read = try {
                    handle.read(chunk, 0, chunk.size)
                } catch (typed: VaultContentException) {
                    return@withContent contentFailure(typed.failure)
                }
                if (read <= 0) break
                collector.write(chunk, 0, read)
            }
            val text = String(collector.bytes, Charsets.UTF_8)
            NivaraResult.Success(
                VaultTextPreview(
                    text = text,
                    characters = text.length,
                    truncated = collector.truncated,
                ),
            )
        }

    override suspend fun openDocument(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultPdfSession> {
        release()
        val ready = CompletableDeferred<NivaraResult<VaultPdfSession>>()
        borrow = scope.launch {
            val outcome = reader.withContent<Unit>(itemId, sizeBytes, authorize) { handle ->
                when (val opened = openRenderer(handle = handle, sizeBytes = sizeBytes)) {
                    is NivaraResult.Success -> {
                        session = opened.value
                        ready.complete(NivaraResult.Success(opened.value))
                        // The block stays until the viewer closes the document: this is what keeps
                        // the key borrowed exactly as long as the document is on screen.
                        opened.value.awaitFinished()
                        NivaraResult.Success(Unit)
                    }

                    is NivaraResult.Failure -> opened
                }
            }
            if (!ready.isCompleted) {
                ready.complete(outcome.viewerFailure()?.let { failure -> viewerFailure(failure) }
                    ?: viewerFailure(VaultViewerFailure.DecodeFailed))
            }
        }
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
     * Builds the renderer over a proxy descriptor, or reports why it could not be built.
     *
     * A refusal here is about the *document*, not the vault: the vault's own failures have already
     * been ruled out, because the content was served before the renderer ever saw it.
     */
    private suspend fun openRenderer(
        handle: VaultContentHandle,
        sizeBytes: Long,
    ): NivaraResult<PdfSession> = withContext(dispatcher) {
        val descriptors = VaultProxyFileDescriptor(
            context = context,
            handle = handle,
            declaredSizeBytes = sizeBytes,
        )
        try {
            val fileDescriptor = descriptors.open()
                ?: throw IllegalStateException("the platform refused a proxy file descriptor")
            val renderer = PdfRenderer(fileDescriptor)
            NivaraResult.Success(PdfSession(renderer = renderer, descriptors = descriptors))
        } catch (cancellation: CancellationException) {
            descriptors.close()
            throw cancellation
        } catch (refused: Exception) {
            descriptors.close()
            // The document could not be opened as a PDF: a file this stage cannot show, not a damaged
            // vault.
            viewerFailure(VaultViewerFailure.DecodeFailed)
        }
    }

    override fun release() {
        session?.release()
        session = null
        borrow?.cancel()
        borrow = null
    }

    /**
     * One open PDF: the platform's renderer over the proxy descriptor, and the signal that ends the
     * key borrow when the viewer closes the document.
     */
    private inner class PdfSession(
        private val renderer: PdfRenderer,
        private val descriptors: VaultProxyFileDescriptor,
    ) : VaultPdfSession {

        private val finished = CompletableDeferred<Unit>()

        override val pageCount: Int
            get() = runCatching { renderer.pageCount }.getOrDefault(0)

        override suspend fun renderPage(
            index: Int,
            maximumDimension: Int,
        ): NivaraResult<VaultDecodedImage> = withContext(dispatcher) {
            val page = try {
                renderer.openPage(index)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (refused: Exception) {
                return@withContext viewerFailure(VaultViewerFailure.DecodeFailed)
            }
            try {
                val longest = max(page.width, page.height).coerceAtLeast(1)
                val dimensionScale = min(1f, maximumDimension.toFloat() / longest.toFloat())
                val pixels = page.width.toDouble() * page.height.toDouble()
                val pixelScale = if (pixels <= VaultImageBounded.MAXIMUM_PIXELS.toDouble()) {
                    1f
                } else {
                    (sqrt(VaultImageBounded.MAXIMUM_PIXELS.toDouble() / pixels)).toFloat()
                }
                val scale = min(dimensionScale, pixelScale)
                val width = max((page.width * scale).toInt(), 1)
                val height = max((page.height * scale).toInt(), 1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // A page has no background of its own; without this, dark text on a transparent
                // bitmap is unreadable over a light screen.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                NivaraResult.Success(
                    VaultDecodedImage(
                        bitmap = bitmap,
                        width = bitmap.width,
                        height = bitmap.height,
                        sampled = scale < 1f,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (refused: Exception) {
                viewerFailure(VaultViewerFailure.DecodeFailed)
            } finally {
                runCatching { page.close() }
            }
        }

        suspend fun awaitFinished() {
            finished.await()
        }

        /** The contract's release: the renderer, the descriptor and the borrow's signal, once. */
        override fun release() {
            if (finished.isCompleted) return
            runCatching { renderer.close() }
            runCatching { descriptors.close() }
            finished.complete(Unit)
        }
    }

    private companion object {

        /**
         * How much text a preview keeps.
         *
         * Enough for a long document to be recognisable and scrollable, bounded so that a text file
         * of a gigabyte costs the same as one of a megabyte. The screen says when it shows a part.
         */
        const val MAXIMUM_TEXT_BYTES = 256 * 1024

        /** Bytes per read through the handle. */
        const val READ_CHUNK_BYTES = 64 * 1024
    }
}

/**
 * A seekable file descriptor whose bytes come from the vault's decrypted content.
 *
 * The platform's own mechanism (`StorageManager.openProxyFileDescriptor`) is what makes it possible
 * to satisfy a renderer that insists on a file descriptor without ever writing plaintext to storage:
 * the kernel exposes a descriptor to the renderer, and every read of it is dispatched back here, where
 * it is served from the bounded, authenticated decryption in order. Nothing is cached, nothing is on
 * disk, and the descriptor is closed with the viewer.
 *
 * A read that cannot be served reports the end of the file rather than an error, because the
 * descriptor protocol has no other way to say it: the renderer then refuses a document it cannot read
 * completely, which is exactly the safe direction — a page is never drawn from a partial file.
 */
internal class VaultProxyFileDescriptor(
    context: Context,
    private val handle: VaultContentHandle,
    private val declaredSizeBytes: Long,
) : ProxyFileDescriptorCallback() {

    private val storageManager: StorageManager? =
        context.applicationContext.getSystemService(StorageManager::class.java)

    private var thread: HandlerThread? = null

    private var descriptor: ParcelFileDescriptor? = null

    private var cursor: Long = 0L

    private val skip = ByteArray(SKIP_BYTES)

    /** The descriptor, opened on first use, or `null` when the platform refuses to make one. */
    fun open(): ParcelFileDescriptor? {
        val manager = storageManager ?: return null
        val worker = HandlerThread(THREAD_NAME).apply { start() }
        return try {
            val opened = manager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                this,
                Handler(worker.looper),
            )
            thread = worker
            descriptor = opened
            opened
        } catch (refused: Exception) {
            // The descriptor was refused: the thread that would have served it has no reason to live.
            worker.quitSafely()
            null
        }
    }

    override fun onGetSize(): Long = declaredSizeBytes

    /**
     * Writes never arrive, and are refused if one ever does.
     *
     * The descriptor is opened read-only, so nothing in the vault can be written through a viewer.
     * The platform declares this callback abstract, and answering it with a success would be a lie
     * about a write that did not happen: the errno a read-only descriptor produces is returned
     * instead, which is exactly what a real read-only file would say.
     */
    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
        throw ErrnoException("onWrite", OsConstants.EBADF)

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (size <= 0) return 0
        return runBlocking {
            try {
                if (offset != cursor) moveTo(offset)
                val read = handle.read(data, 0, size)
                if (read > 0) cursor += read
                // The descriptor protocol reads zero at the end of a file. The vault's -1 and a read
                // that cannot continue both become that zero: a file that ends early, which the
                // renderer refuses rather than draws.
                if (read < 0) 0 else read
            } catch (typed: VaultContentException) {
                0
            }
        }
    }

    override fun onRelease() {
        thread?.quitSafely()
    }

    /** Closes the descriptor and its serving thread; the content handle belongs to the engine. */
    fun close() {
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { thread?.quitSafely() }
        thread = null
    }

    private suspend fun moveTo(position: Long) {
        if (position < cursor) {
            handle.restart()
            cursor = 0L
        }
        var remaining = position - cursor
        while (remaining > 0L) {
            val requested = minOf(skip.size.toLong(), remaining).toInt()
            val read = handle.read(skip, 0, requested)
            if (read <= 0) return
            cursor += read
            remaining -= read
        }
    }

    private companion object {

        const val THREAD_NAME = "nivara-vault-pdf"

        const val SKIP_BYTES = 64 * 1024
    }
}
