package com.nivara.app.data.vault.viewer

import android.media.MediaDataSource
import com.nivara.app.domain.vault.VaultContentException
import com.nivara.app.domain.vault.VaultContentHandle
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * The decrypted bytes of one item, as the platform's media stack asks for them.
 *
 * ### What the platform wants, and what the vault has
 *
 * `MediaPlayer` reads through `MediaDataSource` by offset: it reads a header, jumps to the end of a
 * file for its index, comes back, then streams forward. The vault's encrypted format is the opposite
 * of random access — a record authenticates in order and names its position — so a reader that
 * accepted an arbitrary offset would be handing out bytes it had not verified.
 *
 * This adapter therefore never accepts a seek as a fact about storage. A position *ahead* of the
 * current one is reached by decrypting and discarding the bytes in between; a position *behind* it
 * restarts the item from its first record. Both are bounded in memory — one fixed skip buffer — and
 * neither ever hands out a byte that was not authenticated. The cost is time, not safety: seeking far
 * forward re-reads the object, which is documented rather than hidden.
 *
 * ### Failures
 *
 * Every vault failure becomes an [IOException] here, because that is the only way to tell a media
 * player that its data source is finished. Nothing is reported as an end of file that is not one: a
 * refused read, a closed gate or bytes that failed authentication all raise, so a player cannot
 * mistake a damaged object for a short one and play a prefix of a file.
 */
internal class VaultMediaDataSource(
    private val handle: VaultContentHandle,
    private val declaredSizeBytes: Long,
) : MediaDataSource() {

    /** The plaintext position of the next byte [handle] will produce. */
    private var cursor: Long = 0L

    /** One fixed buffer used to move the cursor: memory that does not grow with the file. */
    private val skip = ByteArray(SKIP_BYTES)

    override fun getSize(): Long = declaredSizeBytes

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0L || offset < 0 || size < 0 || offset + size > buffer.size) {
            throw IOException("the media source was asked for an invalid range")
        }
        return try {
            runBlocking {
                if (position != cursor) moveTo(position)
                val read = handle.read(buffer, offset, size)
                if (read > 0) cursor += read
                read
            }
        } catch (typed: VaultContentException) {
            // A typed vault failure is not something the media stack can interpret; it is told the
            // data source failed, and the viewer's state keeps the typed reason for the screen.
            throw IOException("the encrypted object could not be read", typed)
        }
    }

    override fun close() {
        // The handle is owned by the engine that opened it, so that a player releasing its data
        // source cannot end a borrow another reader is still using.
    }

    /**
     * Moves the cursor to [position], restarting the item when the position is behind it.
     *
     * Forwards is decrypt-and-discard; backwards is a new pass from the first record. Both keep the
     * authenticated order intact.
     */
    private suspend fun moveTo(position: Long) {
        if (position < cursor) {
            handle.restart()
            cursor = 0L
        }
        var remaining = position - cursor
        while (remaining > 0L) {
            val requested = minOf(skip.size.toLong(), remaining).toInt()
            val read = handle.read(skip, 0, requested)
            if (read <= 0) {
                // The item ended before the requested position: there is nothing there to read.
                return
            }
            cursor += read
            remaining -= read
        }
    }

    private companion object {

        /** Bytes moved per read while the cursor catches up. One buffer, reused. */
        const val SKIP_BYTES = 64 * 1024
    }
}
