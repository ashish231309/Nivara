package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.vault.VaultContentException
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultContentHandle
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultUnreadableReason
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One item's plaintext, produced by the existing streaming decryption as the caller asks for it.
 *
 * ### How it works, and why this way
 *
 * The vault's decryption is a pull from the ciphertext side and a push to the plaintext side: running
 * `decryptStream` means "decrypt this whole object into this sink". A viewer, though, reads when *it*
 * wants to — an image decoder asks for bytes until it has its dimensions, a media player asks for the
 * bytes at an offset it has just been asked about — so something has to sit between the two.
 *
 * That something is a pipe of a fixed size (one bounded buffer), with the decryption running as a
 * producer coroutine:
 *
 * ```
 *  storage ──ciphertext──▶ EncryptionService.decryptStream ──▶ bounded pipe ──read()──▶ viewer
 *                                                        producer coroutine
 * ```
 *
 * The point of it is what it is *not*: there is no second decryption loop anywhere in the project —
 * the format is read by the same code the import wrote it with, and a change to the format cannot
 * leave a viewer behind — and nothing is buffered beyond the pipe and the service's own chunk
 * buffers, whatever the size of the file.
 *
 * ### Order, and what restart means
 *
 * The handle is sequential and stays sequential. Records authenticate in order and each one names its
 * position, so a reader that could ask for an arbitrary offset would be asking for bytes it had not
 * verified. [restart] is the honest alternative: it begins the item again — the ciphertext stream is
 * re-opened from storage and decrypted from its first record — which is what a viewer that must scan
 * a file twice does. Seeking *forwards* is the same operation with the intermediate bytes discarded,
 * which is bounded memory but not constant time; callers that seek are told so.
 *
 * ### Endings
 *
 * A clean end of the item is `-1` from [read]. Everything else is a typed [VaultContentException]:
 * the storage refusing the read, the vault's key gone, the session closed between two pieces, or
 * bytes that did not authenticate. Nothing is ever returned as "the end of the item" when it is not.
 */
internal class VaultContentHandleImpl(
    private val itemId: VaultItemId,
    override val sizeBytes: Long,
    private val storage: VaultContentStorage,
    private val entryName: String,
    private val encryptionService: EncryptionService,
    private val key: EncryptionKey,
    private val authorize: () -> Boolean,
) : VaultContentHandle {

    /** Owns the producer. Cancelled when the handle is closed, so no decryption outlives a viewer. */
    private val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var producer: Job? = null
    private var pipe: PipedInputStream? = null
    private var sink: PipedOutputStream? = null

    /**
     * What the producer found, when it did not simply reach the end of the item.
     *
     * Written by the producer and read after the pipe ends; the pipe itself is the hand-off, so the
     * reader always knows whether a short read was an end or a failure.
     */
    @Volatile
    private var producerFailure: VaultContentFailure? = null

    @Volatile
    private var closed = false

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw VaultContentException(VaultContentFailure.Unreadable)
        if (length == 0) return 0
        if (!authorize()) {
            // Asked again for every piece: a session that ends while a viewer is open stops it here,
            // not at the next screen interaction.
            stopPass()
            closed = true
            producerScope.cancel()
            throw VaultContentException(VaultContentFailure.NotAuthorized)
        }

        val source = currentPipe()
        val read = withContext(Dispatchers.IO) {
            try {
                source.read(buffer, offset, length)
            } catch (broken: IOException) {
                // The producer died without closing the pipe cleanly. The recorded failure says why;
                // if there is none, the read failed without the item ending.
                throw VaultContentException(producerFailure ?: VaultContentFailure.Unreadable)
            }
        }
        if (read >= 0) return read

        // The pipe ended. If the producer recorded a reason, the item did not end: it failed.
        producerFailure?.let { failure -> throw VaultContentException(failure) }
        return -1
    }

    override suspend fun restart() {
        if (closed) throw VaultContentException(VaultContentFailure.Unreadable)
        stopPass()
        producerFailure = null
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        stopPass()
        producerScope.cancel()
    }

    /** The pipe for the pass in progress, starting one when there is none. */
    private fun currentPipe(): PipedInputStream {
        pipe?.let { existing -> return existing }
        val next = PipedInputStream(PIPE_BYTES)
        val nextSink = PipedOutputStream(next)
        pipe = next
        sink = nextSink
        producerFailure = null
        producer = producerScope.launch {
            try {
                val outcome = storage.readObject(entryName) { input ->
                    val decrypted = encryptionService.decryptStream(
                        ciphertext = input,
                        plaintext = nextSink,
                        key = key,
                        context = EncryptionContext.VaultContent,
                        identity = itemId.toBytes(),
                    )
                    if (decrypted is NivaraResult.Failure) {
                        producerFailure = decrypted.error.asContentFailure()
                    }
                }
                if (outcome is NivaraResult.Failure && producerFailure == null) {
                    // The storage refused the read. Whether that is "this item's object is not there"
                    // or "the vault could not be reached" is a different fact, and it is checked.
                    producerFailure = missingOrUnreadable()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (typed: VaultContentException) {
                producerFailure = typed.failure
            } catch (error: Exception) {
                producerFailure = error.asContentFailure()
            } finally {
                // Always closed, whatever happened: a reader blocked on the pipe is released, and a
                // consumer that stopped reading does not keep a decryption coroutine alive. Closing a
                // pipe the reader has already abandoned is not a failure — it is the same ending.
                runCatching { nextSink.close() }
            }
        }
        return next
    }

    /** Cancels the pass in progress and closes its pipe. */
    private fun stopPass() {
        producer?.cancel()
        producer = null
        // The other end may already be gone; the pass is over either way.
        runCatching { sink?.close() }
        sink = null
        pipe = null
    }

    /**
     * Whether the object is absent rather than unreachable.
     *
     * Asked only after a read has already failed, so the ordinary path pays nothing for it: the
     * content area is listed once to tell "the index names a file that is not here" from "the vault's
     * storage cannot be reached", which are different sentences on the screen.
     */
    private suspend fun missingOrUnreadable(): VaultContentFailure {
        val entries = storage.listObjects()
        val listed = entries.valueOrNull() ?: return VaultContentFailure.Unreadable
        val present = listed.any { entry -> entry.name == entryName }
        return if (present) VaultContentFailure.Unreadable else VaultContentFailure.ContentMissing
    }

    private companion object {

        /**
         * Bytes of plaintext in flight at most.
         *
         * One bounded buffer between the decryption and the viewer: large enough that a media player
         * is not made to wait for a chunk it could have had, small enough that it is not a way to
         * hold a file in memory.
         */
        const val PIPE_BYTES = 128 * 1024
    }
}

/** The content failure behind whatever a step threw or reported. */
internal fun Exception?.asContentFailure(): VaultContentFailure = when (this) {
    null -> VaultContentFailure.Unreadable
    is CryptographicFailure ->
        if (this is CryptographicFailure.InvalidKey) {
            VaultContentFailure.KeyUnavailable
        } else {
            VaultContentFailure.Corrupt
        }

    is VaultContentFailure -> this
    is VaultContentException -> failure
    // The vault's own failures, translated into the reader's vocabulary: a key that cannot be
    // borrowed is the one case a viewer's screen names differently, and everything else about an
    // unreachable or damaged vault is the same sentence — the content could not be read.
    is VaultFailure -> when (this) {
        VaultFailure.KeyUnavailable -> VaultContentFailure.KeyUnavailable
        is VaultFailure.VaultUnreadable ->
            if (reason == VaultUnreadableReason.KeyUnavailable) {
                VaultContentFailure.KeyUnavailable
            } else {
                VaultContentFailure.Unreadable
            }

        else -> VaultContentFailure.Unreadable
    }

    is VaultSourceException -> VaultContentFailure.Unreadable
    else -> VaultContentFailure.Unreadable
}

/**
 * The plaintext as an [InputStream], for the platform decoders that insist on one.
 *
 * A thin adapter and nothing else: reads block on the handle and become the same typed failures as
 * `IOException`, which is the only way the platform's image decoder can be told that a file was
 * damaged. It is used inside a data-layer engine, on a dispatcher that is not the main thread, and it
 * does not own the handle — the engine closes that.
 */
internal class VaultContentInputStream(
    private val handle: VaultContentHandle,
) : InputStream() {

    private val single = ByteArray(1)

    override fun read(): Int {
        val read = read(single, 0, 1)
        return if (read <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = kotlinx.coroutines.runBlocking {
        try {
            handle.read(buffer, offset, length)
        } catch (typed: VaultContentException) {
            throw IOException("the encrypted object could not be read", typed)
        }
    }

    override fun close() {
        // The handle belongs to the engine that opened it: a decoder closing its input must not end
        // the read for anything else.
    }
}

/**
 * A sink that keeps at most [maximumBytes] and counts the rest.
 *
 * Used where a document is shown as text: the whole object is decrypted — which is what makes a
 * corrupt one fail rather than show a prefix — while only a bounded preview is kept, and the caller
 * is told that the file is longer than what is shown.
 */
internal class BoundedCollector(private val maximumBytes: Int) : OutputStream() {

    private val kept = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))

    var totalBytes: Long = 0L
        private set

    val bytes: ByteArray get() = kept.toByteArray()

    /** Whether the item was longer than what was kept. */
    val truncated: Boolean get() = totalBytes > maximumBytes

    override fun write(byte: Int) {
        totalBytes += 1
        if (kept.size() < maximumBytes) kept.write(byte)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        totalBytes += length
        val room = maximumBytes - kept.size()
        if (room > 0) kept.write(buffer, offset, minOf(room, length))
    }
}
