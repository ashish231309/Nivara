package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult

/**
 * Why an item could not be opened.
 *
 * These are the failures of *reading* content, and they are deliberately few and separate: a file
 * whose encrypted object is not in the vault, one whose bytes storage refused to hand over, one
 * whose authentication failed and one whose vault cannot be opened are four different facts, and a
 * screen that could not tell them apart would end up describing all four as "something went wrong".
 *
 * Nothing here says anything about the plaintext, the key or the format internals: a failure is what
 * a person may be told, and internals are not.
 */
sealed interface VaultContentFailure {

    /** The session ended — before the read, or between two of its pieces. No bytes are served. */
    data object NotAuthorized : VaultContentFailure

    /** The index names this item, and its encrypted object is not in the vault. */
    data object ContentMissing : VaultContentFailure

    /** The vault's storage could not be reached or refused the read. */
    data object Unreadable : VaultContentFailure

    /**
     * The bytes are there and did not authenticate: a modified header, chunk, ciphertext, sequence
     * or final marker. The object is not shown at all — a prefix of a file is not the file.
     */
    data object Corrupt : VaultContentFailure

    /** The vault's key could not be borrowed: no vault, or a key that cannot be unwrapped. */
    data object KeyUnavailable : VaultContentFailure

    /** There is no vault at the selected root yet, so there is nothing to read. */
    data class VaultNotReady(val vault: VaultState) : VaultContentFailure
}

/**
 * A typed content failure travelling out of a read.
 *
 * Reading returns bytes rather than a result, so a failure either has to be a return value of every
 * call — which would make a read of a video a sequence of results nobody checks — or an exception
 * that carries the same typed failure. This is the second: the failure is still the domain's own
 * type, and a caller that catches it can tell a missing object from a corrupted one.
 */
class VaultContentException(val failure: VaultContentFailure) :
    Exception(failure.toString(), null)

/**
 * Plaintext of one imported item, read in bounded pieces.
 *
 * ### What it is
 *
 * A cursor over the decrypted bytes of one encrypted object. [read] fills the caller's buffer, so
 * the caller decides how much memory an item costs; nothing here can be asked for a whole file.
 * [restart] begins the same item again from its first byte, which is what a viewer that must scan a
 * file twice — an image decoder looking for its dimensions before decoding, a media player seeking
 * backwards — does instead of buffering the plaintext.
 *
 * ### What it never is
 *
 * It is not a stream of ciphertext, not a key, and not a way to reach vault storage: it holds a
 * borrow of the vault key for exactly as long as it is open, and the borrow ends when it is closed.
 * It is sequential by construction — there is no seek — because the encrypted format authenticates
 * every record in order and a reader that skipped ahead would be trusting bytes it had not checked.
 *
 * ### Authorization
 *
 * The reader is asked before every piece is served. A session that ends while a viewer is open
 * therefore stops it at the next read, whatever the screen is doing, and no further plaintext is
 * produced after the gate has closed.
 */
interface VaultContentHandle {

    /**
     * The size the authenticated index recorded for this item, in plaintext bytes.
     *
     * A platform source that must declare a size before it is read — a media player asking how large
     * the content is — is given this number. It is what the index says, and the index is
     * authenticated; the bytes are still checked as they are read, so a wrong number can shorten a
     * read but can never produce content that is not the file.
     */
    val sizeBytes: Long

    /**
     * Reads up to [length] plaintext bytes into [buffer] at [offset].
     *
     * @return the number of bytes read, or `-1` when the item has ended.
     * @throws VaultContentException with the typed failure when the read cannot continue.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Starts the item again at its first byte, replacing any read in progress. */
    suspend fun restart()

    /** Releases the ciphertext stream and ends the key borrow. Safe to call more than once. */
    suspend fun close()
}

/**
 * The vault's content, opened for reading.
 *
 * This is the one way anything outside the vault's own repository reads an imported file, and it is
 * the boundary the viewers stand behind: a viewer is handed a [VaultContentHandle] and never sees a
 * key, a cipher, a storage handle or a wrapped blob. The decryption itself stays in the encryption
 * service the vault already uses — this contract only says *which item* and *in what pieces*.
 *
 * ### Why it is scoped rather than open/close
 *
 * Opening content borrows the vault's key, and a borrow is exactly as long as the work it was made
 * for. A contract that returned a handle the caller had to close would leave the key alive across
 * calls that never close it — a leaked viewer would leak the key. Here the borrow *is* the block:
 * when [withContent] returns, the handle is closed and the key is cleared, whatever path the block
 * took.
 */
interface VaultContentReader {

    /**
     * Runs [block] with [itemId]'s content open, closing it afterwards.
     *
     * @param itemId the item to read. The object is named from this identifier and nothing else, so
     *   no name, path or source reference takes part in finding it.
     * @param sizeBytes the plaintext size the index recorded, handed to callers that must declare one
     *   before reading (see [VaultContentHandle.sizeBytes]). Never trusted as a bound.
     * @param authorize asked before any byte is served, and again before every later piece. Returning
     *   `false` fails the read with [VaultContentFailure.NotAuthorized] and serves nothing further.
     * @param block receives the open handle. Its result is returned; the handle must not escape the
     *   block, and a caller that keeps it will find it closed.
     */
    suspend fun <T> withContent(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
        block: suspend (VaultContentHandle) -> NivaraResult<T>,
    ): NivaraResult<T>
}
