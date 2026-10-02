package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultItemId
import java.io.InputStream
import java.io.OutputStream

/**
 * The vault's content area as the import needs it: encrypted objects, written in pieces, named after
 * the item they belong to.
 *
 * Like [VaultRootStorage] this is a seam, and it is deliberately object-shaped rather than
 * file-shaped: it speaks of an item's encrypted object, not of paths, and the platform's own
 * document API stays below it. Everything above it can be tested on the JVM, including the failures
 * that matter most — a destination that refuses a write, one that accepts a write and stores
 * something else, one that cannot rename — because a test double can produce them on demand.
 *
 * The port says nothing about *how* an object is written, but it makes two promises the rest of the
 * stage depends on: an object is never visible under its final name until it is complete, and a
 * write that fails leaves nothing under a name a reader would trust.
 */
internal interface VaultContentStorage {

    /**
     * Lists what is in the content area.
     *
     * A name that is not one of Nivara's own is reported as foreign rather than hidden: it is
     * somebody's file in a folder Nivara shares, and it is neither an item nor something Nivara may
     * delete. A name that looks like an object left part-way through being written is reported as
     * unfinished for the same reason.
     */
    suspend fun listObjects(): NivaraResult<List<ContentObjectRef>>

    /**
     * Writes one item's encrypted object, and does not return until it is complete under its final
     * name.
     *
     * [produce] is handed the stream to write the ciphertext to. The implementation must write to a
     * name that is not an object's final name, and only after [produce] returns and the bytes have
     * been flushed towards storage may the object take its final name. A [produce] that throws, or a
     * write that fails, must leave no object under the final name.
     */
    suspend fun writeObject(itemId: VaultItemId, produce: suspend (OutputStream) -> Unit): NivaraResult<Unit>

    /**
     * Opens a named child of the content area and hands its bytes to [consume].
     *
     * The stream is closed when [consume] returns or throws, and it is a read: nothing is created,
     * changed or deleted by looking at an object.
     */
    suspend fun readObject(entryName: String, consume: suspend (InputStream) -> Unit): NivaraResult<Unit>

    /**
     * Removes a named child of the content area. Deleting what is not there succeeds.
     *
     * Used for exactly one thing in this stage: an object that *this* import wrote and then could
     * not commit, because the session closed before the change was authorized. Nothing else in
     * Nivara deletes content — an object that an index does not name is left where it is, and the
     * stage that owns trash and restore will decide what to do with it, deliberately.
     */
    suspend fun deleteObject(entryName: String): NivaraResult<Unit>
}

/** One child of the content area, as far as its name can say. */
internal sealed interface ContentObjectRef {

    /** The child's name inside the content area. */
    val name: String

    /** What the provider reports as its size, or `null` when it reports none. */
    val sizeBytes: Long?

    /** An encrypted object of [itemId], which is derived from the object's name. */
    data class Object(
        override val name: String,
        val itemId: VaultItemId,
        override val sizeBytes: Long?,
    ) : ContentObjectRef

    /** An object that was left part-way through being written. Never an item, never deleted here. */
    data class Pending(
        override val name: String,
        override val sizeBytes: Long?,
    ) : ContentObjectRef

    /** Something in the content area that Nivara did not put there. Never an item, never deleted. */
    data class Foreign(
        override val name: String,
        override val sizeBytes: Long?,
    ) : ContentObjectRef
}

/**
 * How an item's encrypted object is named.
 *
 * The name is derived from the item's identifier and from nothing else, and the original file name
 * never appears in it: the vault's content area holds identifiers, and what a file was called stays
 * in the authenticated index where it belongs. That is also what makes a name impossible to forge
 * from user input — a file called `../../something` imports as an id, exactly like any other.
 *
 * The pending suffix is how an object that was never completed is recognised. It is a *different*
 * name, so a half-written object can never be read as an item, and it is derived from the same
 * identifier, so a reader can tell which item it was meant to be without trusting its contents.
 */
internal object VaultContentNames {

    /** The suffix of a completed encrypted object. */
    const val OBJECT_SUFFIX = ".nvo"

    /** The suffix of an object that is still being written. */
    const val PENDING_SUFFIX = ".pending"

    /** The name of [itemId]'s encrypted object. */
    fun objectName(itemId: VaultItemId): String = itemId.value + OBJECT_SUFFIX

    /** The name an item's object has while it is being written. */
    fun pendingName(itemId: VaultItemId): String = objectName(itemId) + PENDING_SUFFIX

    /** The item an object's name belongs to, or `null` when the name is not one of Nivara's. */
    fun itemIdOf(name: String): VaultItemId? {
        if (!name.endsWith(OBJECT_SUFFIX)) return null
        val id = name.removeSuffix(OBJECT_SUFFIX)
        return if (VaultItemId.isWellFormed(id)) VaultItemId(id) else null
    }

    /** The name an object has while it is being written, or `null` when it is not a pending one. */
    fun pendingBaseOf(name: String): String? {
        if (!name.endsWith(PENDING_SUFFIX)) return null
        val base = name.removeSuffix(PENDING_SUFFIX)
        return if (itemIdOf(base) != null) base else null
    }

    /** Classifies [name] as Nivara would read it. */
    fun classify(name: String, sizeBytes: Long?): ContentObjectRef = when {
        pendingBaseOf(name) != null -> ContentObjectRef.Pending(name = name, sizeBytes = sizeBytes)
        else -> itemIdOf(name)?.let { itemId ->
            ContentObjectRef.Object(name = name, itemId = itemId, sizeBytes = sizeBytes)
        } ?: ContentObjectRef.Foreign(name = name, sizeBytes = sizeBytes)
    }
}
