package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult

/**
 * The vault root as the repository needs it: names, bytes, and typed trouble.
 *
 * This is the seam between the vault's logic and whatever is actually storing the folder. The logic
 * above it is Android-free and testable on the JVM — including the failure paths that matter most,
 * because a test double can refuse a write, fail a read-back or disappear mid-initialization — and
 * the implementation below it is the platform's own storage, where every awkward detail of the
 * Storage Access Framework lives and stays.
 *
 * The port is deliberately *document-shaped* rather than path-shaped: it speaks of a metadata area
 * with named children, not of files, directories or URIs. That is the vocabulary the vault actually
 * has, and it is what keeps `File`, `Uri`, `DocumentFile` and `ParcelFileDescriptor` out of everything
 * except the one class that talks to the platform.
 */
internal interface VaultRootStorage {

    /**
     * Creates the metadata area if it is not there, and reports nothing about a vault.
     *
     * A separate step from [metadataEntries] on purpose: reading must never create anything, so the
     * two are different calls and only the one that may write is used while initializing.
     */
    suspend fun ensureMetadataArea(): NivaraResult<Unit>

    /** Creates the content area if it is not there. */
    suspend fun ensureContentArea(): NivaraResult<Unit>

    /**
     * Whether the metadata area exists.
     *
     * Separate from [metadataEntries] on purpose: "this folder holds nothing Nivara made" and "Nivara's
     * structure is here and holds no record" are different facts about a root, and a reader that could
     * not tell them apart would report an unfinished initialization as an empty folder.
     */
    suspend fun metadataAreaExists(): NivaraResult<Boolean>

    /**
     * The names of the children of the metadata area.
     *
     * A name is all a reader needs — it decides which document to read, and reading is bounded — and
     * nothing here reports a size, because nothing in this stage makes a decision from one.
     *
     * A missing metadata area is a success with an empty list: the ordinary state of a folder the user
     * has just selected. Anything else that goes wrong (unreachable storage, a refused grant) is a
     * failure, never an empty list, because "I could not look" and "there is nothing there" must not
     * be the same answer.
     */
    suspend fun metadataEntries(): NivaraResult<List<String>>

    /** Whether the content area exists, for validating a vault's structure. */
    suspend fun contentAreaExists(): NivaraResult<Boolean>

    /** Reads a named child of the metadata area. */
    suspend fun readMetadata(entryName: String): NivaraResult<ByteArray>

    /**
     * Replaces a named child of the metadata area with [bytes].
     *
     * The implementation must make the replacement as close to atomic as the platform allows and must
     * never leave a half-written document under a name a reader would trust: on the Storage Access
     * Framework that means deleting the *older* slot and creating a fresh document in its place, so
     * the record a reader could pick is never the one being written.
     */
    suspend fun writeMetadata(entryName: String, bytes: ByteArray): NivaraResult<Unit>

    /** Deletes a named child of the metadata area. Deleting what is not there succeeds. */
    suspend fun deleteMetadata(entryName: String): NivaraResult<Unit>
}

/**
 * The metadata area and the content area, as they appear inside the root the user selected.
 *
 * Two names and no more. Stage 13 creates exactly these two directories: the metadata area, which
 * holds the authenticated record, and the content area, which later stages fill with encrypted files.
 * A trash area, an album area, a thumbnail area and an index area are all deliberately absent — they
 * arrive with the stages that use them, and creating them now would be inventing structure nothing
 * reads.
 */
internal object VaultStructure {

    /** Holds the metadata record. */
    const val METADATA_DIRECTORY = "nivara.meta"

    /** Reserved for encrypted content. Empty in this stage. */
    const val CONTENT_DIRECTORY = "nivara.content"

    /**
     * The two record slots.
     *
     * Two, because a record has to be replaceable without ever truncating the one a reader would use:
     * a write goes to the slot that is *not* the current record, and the generation inside the record
     * decides which of the two is authoritative. See `NivaraVaultRepository`.
     */
    val SLOT_NAMES: List<String> = listOf("vault.0.nvm", "vault.1.nvm")

    /**
     * The two content-index slots, written exactly like the vault's own record and for the same
     * reason: a new index generation goes into the slot that is not the current one, so the list a
     * reader would pick is never the one being written.
     *
     * They live in the metadata area rather than beside the objects they describe, because they *are*
     * metadata: a small authenticated record about the vault, not content. Keeping them there also
     * keeps the content area's listing meaningful — everything in it is an object, an unfinished
     * object or a file Nivara did not put there.
     */
    val INDEX_SLOT_NAMES: List<String> = listOf("index.0.nvi", "index.1.nvi")

    /**
     * The two organisation-record slots — the vault's albums.
     *
     * A third pair, written exactly like the other two and for exactly the same reason: a new album
     * generation goes into the slot that is not the current one, so the record a reader would pick is
     * never the record being written. They live in the metadata area with the rest because they *are*
     * metadata — a small authenticated record about the vault — and the content area's listing must
     * keep meaning "objects, and nothing else".
     *
     * The marker inside them is `NVAO` rather than the index's `NVIN`, and they are sealed under a
     * purpose of their own, so an album record and an index record can never be mistaken for each
     * other even if one were moved onto the other's name.
     */
    val ORGANIZATION_SLOT_NAMES: List<String> = listOf("albums.0.nva", "albums.1.nva")

    /**
     * The two trash-record slots — the items the vault has moved out of the active collection.
     *
     * A fourth pair, written exactly like the other three and for exactly the same reason: a new trash
     * generation goes into the slot that is not the current one, so the record a reader would pick is
     * never the record being written. They live in the metadata area with the rest because they *are*
     * metadata — a small authenticated record about the vault — and the content area's listing must
     * keep meaning "objects, and nothing else".
     *
     * The marker inside them is `NVTR` rather than the index's `NVIN` or the album record's `NVAO`, and
     * they are sealed under a purpose of their own, so a trash record can never be mistaken for either
     * of the others even if one were moved onto the other's name.
     */
    val TRASH_SLOT_NAMES: List<String> = listOf("trash.0.nvt", "trash.1.nvt")
}
