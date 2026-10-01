package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.vault.VaultContentSource
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.testing.keyOf
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream

/**
 * Test doubles for the content path.
 *
 * The same idea as the metadata doubles next to them: the failures that matter are the ones a device
 * cannot be asked to produce on command — a destination that refuses a write part way through, one
 * that reports success and stores nothing, one that stores something else, one that cannot rename, a
 * source that stops being readable half way. Each is a switch here, so the import pipeline's promises
 * are checked rather than assumed.
 *
 * [FakeVaultContentStorage] behaves like the platform in the two places the design depends on: an
 * object is never visible under its final name until the write has finished, and a write that fails
 * leaves nothing behind under a name a reader would trust.
 */
internal class FakeVaultContentStorage : VaultContentStorage {

    /** Every child of the content area, by name — including objects still being written. */
    val documents: MutableMap<String, ByteArray> = linkedMapOf()

    var listFailure: VaultFailure? = null
    var writeFailure: VaultFailure? = null
    var readFailure: VaultFailure? = null
    var deleteFailure: VaultFailure? = null

    /** Bytes accepted before a write is refused: an interruption part way through. */
    var writeFailureAfterBytes: Int? = null

    /** A write that reports success and stores nothing, like a provider that dropped it. */
    var swallowWrites: Boolean = false

    /** A write that reports success and stores something else. */
    var corruptWrites: Boolean = false

    /** A provider that will not give the object its final name. */
    var renameRefused: Boolean = false

    var writeCalls: Int = 0
    var readCalls: Int = 0
    var deleteCalls: Int = 0

    /**
     * Called when an object has taken its final name, so a test can assert what else has — and has
     * not — happened at that moment. It is how "the index is only written about a complete object" is
     * checked as a fact rather than as an intention.
     */
    var onObjectFinalized: (() -> Unit)? = null

    override suspend fun listObjects(): NivaraResult<List<ContentObjectRef>> {
        listFailure?.let { failure -> return NivaraResult.Failure(failure) }
        return NivaraResult.Success(
            documents.entries.map { (name, bytes) ->
                VaultContentNames.classify(name = name, sizeBytes = bytes.size.toLong())
            },
        )
    }

    override suspend fun writeObject(
        itemId: VaultItemId,
        produce: suspend (OutputStream) -> Unit,
    ): NivaraResult<Unit> {
        writeCalls += 1
        writeFailure?.let { failure -> return NivaraResult.Failure(failure) }

        val pendingName = VaultContentNames.pendingName(itemId)
        val objectName = VaultContentNames.objectName(itemId)
        // The platform creates the document under its temporary name before any byte is written, so
        // the double does too: it is what makes "a failed or cancelled write leaves nothing behind"
        // a fact about storage rather than a promise about a call.
        documents.remove(pendingName)
        documents[pendingName] = ByteArray(0)

        val buffer = java.io.ByteArrayOutputStream()
        val sink = if (writeFailureAfterBytes != null) {
            LimitedSink(buffer, writeFailureAfterBytes!!)
        } else {
            buffer
        }
        try {
            produce(sink)
        } catch (cancellation: CancellationException) {
            // The platform deletes the pending document and lets the cancellation through, so a
            // cancelled import is cancelled rather than reported as a failed one.
            documents.remove(pendingName)
            throw cancellation
        } catch (failure: Exception) {
            // The platform deletes the pending document when a write fails; so does this.
            documents.remove(pendingName)
            return NivaraResult.Failure(failure.asStoredFailure())
        }

        val stored = buffer.toByteArray()
        documents[pendingName] = stored
        if (swallowWrites) {
            documents.remove(pendingName)
            return NivaraResult.Success(Unit)
        }
        if (renameRefused) {
            documents.remove(pendingName)
            return NivaraResult.Failure(VaultFailure.WriteFailed)
        }

        val objectBytes = if (corruptWrites) {
            stored.copyOf().also { bytes -> bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte() }
        } else {
            stored
        }
        documents.remove(pendingName)
        documents[objectName] = objectBytes
        onObjectFinalized?.invoke()
        return NivaraResult.Success(Unit)
    }

    override suspend fun readObject(
        entryName: String,
        consume: suspend (InputStream) -> Unit,
    ): NivaraResult<Unit> {
        readCalls += 1
        readFailure?.let { failure -> return NivaraResult.Failure(failure) }
        val bytes = documents[entryName] ?: return NivaraResult.Failure(VaultFailure.StorageUnavailable)
        return try {
            ByteArrayInputStream(bytes.copyOf()).use { input -> consume(input) }
            NivaraResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            NivaraResult.Failure(failure.asStoredFailure())
        }
    }

    override suspend fun deleteObject(entryName: String): NivaraResult<Unit> {
        deleteCalls += 1
        deleteFailure?.let { failure -> return NivaraResult.Failure(failure) }
        documents.remove(entryName)
        return NivaraResult.Success(Unit)
    }

    /** Every byte currently stored, for byte-for-byte comparisons across an operation. */
    fun snapshot(): Map<String, List<Int>> = documents.mapValues { (_, bytes) ->
        bytes.map { byte -> byte.toInt() }
    }
}

/** A sink that stops accepting bytes after [maximum] of them, like a destination that filled up. */
private class LimitedSink(
    private val delegate: OutputStream,
    private val maximum: Int,
) : OutputStream() {

    private var written = 0

    override fun write(byte: Int) {
        accept(1)
        delegate.write(byte)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        accept(length)
        delegate.write(buffer, offset, length)
    }

    override fun flush() = delegate.flush()

    private fun accept(count: Int) {
        if (written + count > maximum) throw IOException("the destination refused the write")
        written += count
    }
}

/** Hands the pipeline a source it decides: bytes, declared metadata, and how reading fails. */
internal class FakeVaultSourceOpener(
    var source: VaultContentSource? = FakeVaultContentSource(bytes = "hello vault".toByteArray(), name = "notes.txt"),
    var failure: VaultImportFailure? = null,
) : VaultSourceOpener {

    var openCalls: Int = 0

    /** Documents by the reference that names them, for tests that import more than one at a time. */
    val sources: MutableMap<String, VaultContentSource> = linkedMapOf()

    override suspend fun open(reference: VaultSourceReference): NivaraResult<VaultContentSource> {
        openCalls += 1
        failure?.let { typed -> return NivaraResult.Failure(typed) }
        sources[reference.value]?.let { perReference -> return NivaraResult.Success(perReference) }
        val opened = source ?: return NivaraResult.Failure(VaultImportFailure.SourceUnavailable)
        return NivaraResult.Success(opened)
    }
}

/**
 * A document in memory, read in the pieces the caller asks for.
 *
 * The failure switches are the ones a provider can produce: a source that stops being readable part
 * way through, and a declared size that disagrees with the bytes. Both matter because the import's
 * promises are about what was actually read — a shortened read must fail the import rather than
 * produce a smaller file that authenticates.
 */
internal class FakeVaultContentSource(
    private val bytes: ByteArray,
    override val displayName: String,
    override val mimeType: String? = "text/plain",
    override val declaredSizeBytes: Long? = bytes.size.toLong(),
    private val failAfterBytes: Long? = null,
    private val failure: VaultImportFailure = VaultImportFailure.SourceUnavailable,
) : VaultContentSource {

    private var position = 0

    /** Whether the source was closed, so a test can prove the import released it. */
    var closed: Boolean = false
        private set

    /** How many times the pipeline asked for bytes — bounded reads, not one big one. */
    var readCalls: Int = 0
        private set

    override suspend fun read(buffer: ByteArray): Int {
        readCalls += 1
        failAfterBytes?.let { limit ->
            if (position >= limit) throw VaultSourceException(failure)
        }
        if (position >= bytes.size) return -1
        val count = minOf(buffer.size, bytes.size - position)
        bytes.copyInto(buffer, 0, position, position + count)
        position += count
        return count
    }

    override suspend fun close() {
        closed = true
    }
}

/**
 * Lends the same vault key every time, as the real repository does.
 *
 * Each borrow gets a fresh copy of the same material, so a test that imports a file and then reads
 * the index back is using one key — and a key that was cleared after its borrow cannot make the next
 * read fail, which is exactly what would happen if this double handed out one shared object.
 */
internal class FakeVaultKeyAccess(
    var location: VaultLocation = VaultLocation("content://com.nivara.test/tree/vault"),
    var failure: VaultFailure? = null,
    private val material: ByteArray = ByteArray(32) { index -> (index + 7).toByte() },
) : VaultKeyAccess {

    var borrows: Int = 0
        private set

    /** The keys handed out, so a test can prove they were cleared afterwards. */
    val handedOut: MutableList<EncryptionKey> = mutableListOf()

    override suspend fun <T> withVaultKey(
        block: suspend (location: VaultLocation, key: EncryptionKey) -> NivaraResult<T>,
    ): NivaraResult<T> {
        borrows += 1
        failure?.let { refused -> return NivaraResult.Failure(refused) }
        val key = keyOf(material.copyOf(), label = "vault-key")
        handedOut += key
        return try {
            block(location, key)
        } finally {
            (key as? EncryptionKey.InProcess)?.clear()
        }
    }
}

/**
 * A vault that is ready to hold content, without any storage behind it.
 *
 * The pipeline asks the vault's repository only one question — can the vault be opened at all — and
 * the answer is what a test needs to vary: a ready vault, a missing one, one whose records cannot be
 * read.
 */
internal class FakeReadyVaultRepository(
    private var state: VaultState = VaultState.Ready(
        identity = VaultIdentity.create(SecureRandomGenerator()),
        formatVersion = 1,
    ),
) : VaultRepository {

    override suspend fun inspect(): VaultState = state

    override suspend fun initialize(replaceUnreadable: Boolean): NivaraResult<Unit> =
        NivaraResult.Failure(VaultFailure.VaultAlreadyExists)

    /** Reports a different vault, for the tests about a vault that is not ready. */
    fun report(newState: VaultState) {
        state = newState
    }
}

/**
 * The failure behind an exception a double threw while it was pretending to be storage.
 *
 * The platform layer translates its failures before they travel, and it passes a source failure and a
 * refused write through unchanged; the double does the same, so a test sees the same vocabulary the
 * device would produce.
 */
private fun Exception.asStoredFailure(): Exception = when (this) {
    is VaultFailure -> this
    is VaultImportFailure -> this
    is VaultSourceException -> failure
    is VaultImportException -> failure
    else -> VaultFailure.WriteFailed
}
