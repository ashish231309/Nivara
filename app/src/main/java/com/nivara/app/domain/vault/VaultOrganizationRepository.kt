package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult

/**
 * What the record that holds a vault's albums says.
 *
 * ### Its own fact, kept apart from the index
 *
 * Organisation metadata is a second small record beside the vault's index, and like the index it can
 * be absent, readable, damaged, written by a newer build or unreachable. None of those may be drawn as
 * "no albums yet": an album list that replaced an unreadable record with an empty one would invite a
 * person to start organising again over the top of albums that are still there — and the write that
 * followed would replace them.
 *
 * [Missing] is therefore the ordinary state of a vault nobody has organised yet, and it is distinct
 * from every unreadable state on purpose.
 */
sealed interface VaultOrganizationState {

    /** The vault itself is not ready, so its organisation record was not read. */
    data class VaultNotReady(val vault: VaultState) : VaultOrganizationState

    /** No organisation record exists: no album has ever been created in this vault. */
    data object Missing : VaultOrganizationState

    /**
     * A readable organisation record.
     *
     * @param albums the albums, in the order they were created. A screen may draw them in another
     *   deterministic order ([VaultOrdering]), but the stored order is preserved so that a record
     *   written back after a mutation is the same record plus the change.
     */
    data class Ready(val albums: List<VaultAlbum>) : VaultOrganizationState {

        /** Whether the vault holds no albums at all. */
        val isEmpty: Boolean get() = albums.isEmpty()

        /** Whether an album with [albumId] is in this record. */
        fun album(albumId: VaultAlbumId): VaultAlbum? =
            albums.firstOrNull { album -> album.id == albumId }
    }

    /** A record exists and cannot be read. */
    data class Unreadable(val reason: VaultOrganizationUnreadable) : VaultOrganizationState

    /** A record exists and was written by a newer Nivara. Never written over. */
    data class UnsupportedVersion(val fileVersion: Int) : VaultOrganizationState

    /** The record could not be read because the storage it lives on cannot be reached. */
    data object Unavailable : VaultOrganizationState

    /** The platform refuses access to the vault root, so the record cannot be read. */
    data object AccessDenied : VaultOrganizationState

    /**
     * Whether organisation metadata can be changed from this state.
     *
     * Only a record that was read, or the certain knowledge that none exists yet, may be built on:
     * mutating anything else would mean writing over a record Nivara could not read, which is how
     * somebody's albums disappear.
     */
    val acceptsChanges: Boolean get() = this is Missing || this is Ready
}

/**
 * Why an organisation record that exists cannot be read.
 *
 * The same two facts the index distinguishes, for the same reason: a record that is structurally
 * wrong or did not authenticate with this vault's key is damaged, and a key that cannot be obtained
 * at all is a different situation with a different remedy.
 */
enum class VaultOrganizationUnreadable {

    /** The record is malformed, or it did not authenticate under this vault's key. */
    MetadataDamaged,

    /** The vault key that opens the record is not available. */
    KeyUnavailable,
}

/**
 * Everything that can go wrong when the vault's organisation is read or changed.
 *
 * Each case is a promise about what did *not* happen. No case means "the change was made and
 * something else went wrong": a mutation reports success only after the new record has been written,
 * read back, authenticated, decoded and compared with what was intended, so a caller that is told an
 * album was created can show it.
 *
 * Like every other typed failure in Nivara the messages are fixed, non-secret strings — no path, no
 * URI, no album or item name, no key material.
 */
sealed class VaultOrganizationFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The user does not currently have a valid session.
     *
     * Organisation changes the vault, so it is authorized exactly the way importing is: through the
     * session that already exists. There is no album password and no separate prompt.
     */
    data object NotAuthorized :
        VaultOrganizationFailure("changing albums needs an open session")

    /** The vault is not ready to hold albums: no folder chosen, an unopenable record, a newer build. */
    data class VaultNotReady(val vault: VaultState) :
        VaultOrganizationFailure("the vault is not ready for albums")

    /**
     * The organisation record that exists cannot be read.
     *
     * Changing it is refused rather than starting a fresh one: a new record would replace the albums
     * that are still there, and the record is left exactly as it was found.
     */
    data class OrganizationUnreadable(val reason: VaultOrganizationUnreadable) :
        VaultOrganizationFailure("the vault's album record cannot be read")

    /** The organisation record was written by a newer Nivara. Never written over. */
    data class UnsupportedVersion(val fileVersion: Int) :
        VaultOrganizationFailure("the vault's album record was written by a newer Nivara")

    /** The organisation metadata could not be read right now — unreachable storage, for instance. */
    data object MetadataUnavailable :
        VaultOrganizationFailure("the vault's album record cannot be reached")

    /** The platform refuses access to the vault root. */
    data object AccessDenied :
        VaultOrganizationFailure("access to the vault's storage was refused")

    /** There is no album with the requested identifier in the readable record. */
    data object AlbumNotFound :
        VaultOrganizationFailure("the album is not in this vault")

    /** The requested title is not usable — empty, blank, absurdly long, or full of control characters. */
    data object InvalidAlbumName :
        VaultOrganizationFailure("the album name cannot be stored")

    /** The album exists and already has the requested title. */
    data object AlbumNameUnchanged :
        VaultOrganizationFailure("the album already has this name")

    /** The album is at [VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM] items and cannot grow. */
    data object AlbumFull :
        VaultOrganizationFailure("the album already holds as many items as it can")

    /** The vault holds as many albums, or as much membership, as one record can carry. */
    data object OrganizationFull :
        VaultOrganizationFailure("the vault cannot hold another album or membership")

    /** The vault root cannot be reached, so the record could not be written. */
    data object StorageUnavailable :
        VaultOrganizationFailure("the vault's storage cannot be reached")

    /** The platform refused a write while the record was being written. */
    data object WriteFailed :
        VaultOrganizationFailure("the vault's storage refused a write")

    /**
     * Something written did not come back as it was written.
     *
     * The record was read again after writing and did not match what was intended: Nivara reports a
     * failure rather than claiming an album exists when the bytes on storage say otherwise.
     */
    data object VerificationFailed :
        VaultOrganizationFailure("what was written did not verify")

    /** The platform key that protects the vault's key material is gone or unusable. */
    data object KeyUnavailable :
        VaultOrganizationFailure("the platform key protecting the vault is not available")

    /** One of the existing cryptographic services refused the operation. */
    data object CryptographyFailed :
        VaultOrganizationFailure("a cryptographic service refused the operation")
}

/**
 * The vault's albums, and the operations that change them.
 *
 * ### It organises references, never content
 *
 * Every operation here touches one small authenticated record in the vault's metadata area. None of
 * them reads, decrypts, moves, renames or deletes an item's encrypted object, and none of them makes
 * an item appear or disappear: an album names items, so deleting an album forgets a list of
 * references and leaves every file exactly where it was. That is why there is no operation in this
 * interface that could delete content, and no [VaultItem] in it at all.
 *
 * ### Reads and writes
 *
 * [read] answers without changing anything and never creates a record. The five mutations are the
 * whole of what a person can do to their albums in this stage: create, rename, delete, add an item
 * and remove an item. Trash, restore and permanent deletion are absent because they belong to the
 * stage that owns them, and an operation nothing asks for is an operation that can lose a file.
 *
 * ### Authorization
 *
 * Every mutation takes an `authorize` function rather than a session: the policy belongs to the
 * caller (the existing session gate), and this layer only refuses to write when it is told no. It is
 * asked before anything is read and again before the new record is committed, so a session that ends
 * mid-operation cannot produce a change that was never authorized.
 */
interface VaultOrganizationRepository {

    /**
     * Reports what the vault's albums are.
     *
     * Reads only: a record that cannot be read is reported as such and left exactly as it was found,
     * never rebuilt, never repaired and never replaced with an empty one.
     */
    suspend fun read(): VaultOrganizationState

    /**
     * Creates an album titled [name].
     *
     * @return the album as it was committed, so a screen can show it without a second read.
     */
    suspend fun createAlbum(
        name: String,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum>

    /** Renames the album [albumId], leaving its membership untouched. */
    suspend fun renameAlbum(
        albumId: VaultAlbumId,
        name: String,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum>

    /**
     * Deletes the album [albumId] and nothing else.
     *
     * The items the album named are untouched: they keep their encrypted objects, their place in the
     * index and their membership in every other album.
     */
    suspend fun deleteAlbum(
        albumId: VaultAlbumId,
        authorize: () -> Boolean,
    ): NivaraResult<Unit>

    /**
     * Adds [itemId] to the album [albumId].
     *
     * Idempotent: an item already in the album leaves the record as it was and is reported as a
     * success, because what the person asked for — the item being in the album — is true either way.
     */
    suspend fun addItem(
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum>

    /**
     * Removes [itemId] from the album [albumId].
     *
     * Idempotent in the same way. Removing a reference from an album never removes an item, and an
     * album that loses its last item stays: it was created on purpose and it stays until somebody
     * deletes it.
     */
    suspend fun removeItem(
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum>
}

/**
 * The failure behind a failed organisation result, when it is one of Nivara's own.
 *
 * A result carries an [Exception] for diagnostics; this reads the typed failure back out of it so a
 * caller can map it to a screen without matching on exception classes of its own.
 */
fun Throwable?.asOrganizationFailure(): VaultOrganizationFailure? = this as? VaultOrganizationFailure
