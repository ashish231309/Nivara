package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult

/**
 * One file the vault has moved out of the active collection, and when it was moved.
 *
 * An entry is a *reference and a moment*: the item's identifier, which is the same identifier it had
 * while it was active and will have again if it is restored, and the instant it left the active
 * collection. It is deliberately not a copy of the item: no name, no type, no size, no digest and no
 * content location. Everything a screen says about a trashed file is read from the vault's
 * authenticated index at the moment it is shown, exactly as it is for a file in an album — which is
 * what makes restore correct by construction. Restoring an item does not recreate anything; it removes
 * one line from this list, and the file it names is unchanged.
 *
 * @property itemId the identifier of the item, unchanged by being trashed and by being restored.
 * @property trashedAtEpochMillis when the item left the active collection, in milliseconds since the
 *   epoch. Used to show when it happened and to order the trash list; never an identity, never a
 *   security decision.
 */
data class VaultTrashEntry(
    val itemId: VaultItemId,
    val trashedAtEpochMillis: Long,
) {

    init {
        require(trashedAtEpochMillis >= 0) { "an item is trashed at an instant after the epoch" }
    }
}

/**
 * What the record that holds a vault's trash says.
 *
 * ### Why this is not a list
 *
 * The same distinction every other vault record makes, for the same reason: *nothing has been moved
 * to trash* and *the list of what was moved cannot be read* are different facts, and drawing the
 * second as the first would tell a person their trash is empty while the record is still there. So
 * [Missing] is the ordinary state of a vault whose owner has never trashed anything, every unreadable
 * state keeps its own name, and nothing here is repaired or replaced.
 *
 * ### What it answers
 *
 * [Ready] answers which items are trashed, and that answer is what lets the rest of the vault show the
 * active collection: the index names everything the vault holds, and this record says which part of it
 * is out of sight. An item can be moved to trash and back without the index, the item's metadata or
 * any album being touched.
 */
sealed interface VaultTrashState {

    /** The vault itself is not ready, so its trash record was not read. */
    data class VaultNotReady(val vault: VaultState) : VaultTrashState

    /**
     * The vault holds no trash record at all.
     *
     * The ordinary state of a vault whose owner has never moved anything to trash, and a valid state
     * to write the first trashed item into. Distinct from a readable record that happens to hold no
     * entries, because the two mean different things to a writer deciding whether it may replace the
     * record.
     */
    data object Missing : VaultTrashState

    /**
     * A readable trash record.
     *
     * @param entries the trashed items, in the record's canonical order. Membership is a set: the
     *   codec refuses a stored record that names one item twice, because a duplicate could only come
     *   from a writer that disagreed with itself.
     */
    data class Ready(val entries: List<VaultTrashEntry>) : VaultTrashState {

        /** How many items are in the trash. */
        val size: Int get() = entries.size

        /** The identifiers this record says are trashed. */
        val trashedItemIds: Set<VaultItemId> get() = entries.map { entry -> entry.itemId }.toSet()

        /** Whether [itemId] is in the trash according to this record. */
        fun contains(itemId: VaultItemId): Boolean = entries.any { entry -> entry.itemId == itemId }

        /** The entry for [itemId], or `null` when this record does not name it. */
        fun entry(itemId: VaultItemId): VaultTrashEntry? =
            entries.firstOrNull { entry -> entry.itemId == itemId }
    }

    /** A record exists and cannot be read. */
    data class Unreadable(val reason: VaultTrashUnreadable) : VaultTrashState

    /** A record exists and was written by a newer Nivara. Never written over. */
    data class UnsupportedVersion(val fileVersion: Int) : VaultTrashState

    /** The record could not be read because the storage it lives on cannot be reached. */
    data object Unavailable : VaultTrashState

    /** The platform refuses access to the vault root, so the record cannot be read. */
    data object AccessDenied : VaultTrashState

    /**
     * Whether the trash can be changed from this state.
     *
     * Only a record that was read, or the certain knowledge that none exists yet, may be built on:
     * writing over anything else would mean replacing a record Nivara could not read, which is how
     * somebody's trash — and with it the knowledge of which files are out of sight — would be lost.
     */
    val acceptsChanges: Boolean get() = this is Missing || this is Ready

    /**
     * Which items are trashed, or `null` when this state cannot say.
     *
     * The active collection is the index's items minus this set, so a state that cannot answer makes
     * the active collection unknowable — and a caller that draws the index anyway has to say so
     * rather than claim every listed file is active. [Missing] answers with nothing, because a vault
     * that never had a record never had anything trashed.
     */
    val trashedItemIdsOrNull: Set<VaultItemId>?
        get() = when (this) {
            Missing -> emptySet()
            is Ready -> trashedItemIds
            else -> null
        }
}

/**
 * Why a trash record that exists cannot be read.
 *
 * The same two facts the index and the album record distinguish, for the same reason: a record that is
 * structurally wrong or did not authenticate with this vault's key is damaged, and a key that cannot
 * be obtained at all is a different situation with a different remedy.
 */
enum class VaultTrashUnreadable {

    /** The record is malformed, or it did not authenticate under this vault's key. */
    MetadataDamaged,

    /** The vault key that opens the record is not available. */
    KeyUnavailable,
}

/**
 * The limits the trash record obeys.
 *
 * Every one of them exists so that a stored record can be decoded without trusting it: a count read
 * from untrusted bytes is a *claim*, and these are the bounds that make the claim checkable before
 * anything is allocated from it. They are also the limits the writing side refuses to exceed, so a
 * record Nivara writes is always one Nivara can read back.
 */
object VaultTrashLimits {

    /**
     * The most items one trash record may name.
     *
     * A bound of the format rather than of the product: the whole record has to fit inside
     * [MAXIMUM_RECORD_BYTES], and a vault that one day needs more than this raises the bound with a
     * new format version rather than growing the record past what it promises to read.
     */
    const val MAXIMUM_TRASHED_ITEMS: Int = 20_000

    /** The largest trash record Nivara will read, sealing included. */
    const val MAXIMUM_RECORD_BYTES: Int = 1024 * 1024
}

/**
 * Everything that can go wrong when the vault's trash is read or changed.
 *
 * Each case is a promise about what did *not* happen. No case means "the change was made and
 * something else went wrong": a mutation reports success only after the new record has been written,
 * read back, authenticated, decoded and compared with what was intended, so a caller that is told an
 * item was moved to trash can show it.
 *
 * Like every other typed failure in Nivara the messages are fixed, non-secret strings — no path, no
 * URI, no file name, no key material.
 */
sealed class VaultTrashFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The user does not currently have a valid session.
     *
     * Trash changes the vault's metadata, so it is authorized exactly the way importing and albums
     * are: through the session that already exists. There is no trash password and no second prompt.
     */
    data object NotAuthorized :
        VaultTrashFailure("changing trash needs an open session")

    /** The vault is not ready to hold trash: no folder chosen, an unopenable record, a newer build. */
    data class VaultNotReady(val vault: VaultState) :
        VaultTrashFailure("the vault is not ready for trash")

    /**
     * The trash record that exists cannot be read.
     *
     * Changing it is refused rather than starting a fresh one: a new record would forget which files
     * are out of sight, and the record is left exactly as it was found.
     */
    data class TrashUnreadable(val reason: VaultTrashUnreadable) :
        VaultTrashFailure("the vault's trash record cannot be read")

    /** The trash record was written by a newer Nivara. Never written over. */
    data class UnsupportedVersion(val fileVersion: Int) :
        VaultTrashFailure("the vault's trash record was written by a newer Nivara")

    /** The trash record could not be read right now — unreachable storage, for instance. */
    data object MetadataUnavailable :
        VaultTrashFailure("the vault's trash record cannot be reached")

    /** The platform refuses access to the vault root. */
    data object AccessDenied :
        VaultTrashFailure("access to the vault's storage was refused")

    /**
     * The vault's own list of files cannot be read, so it cannot be asked what the vault holds.
     *
     * Moving a file to trash is refused while this holds: the operation must be able to prove the file
     * is in the vault, and a list that cannot be read cannot prove anything.
     */
    data class IndexUnreadable(val reason: VaultIndexUnreadable) :
        VaultTrashFailure("the vault's list cannot be read")

    /** The vault's list was written by a newer Nivara, so it cannot be asked what the vault holds. */
    data class IndexUnsupportedVersion(val fileVersion: Int) :
        VaultTrashFailure("the vault's list was written by a newer Nivara")

    /** The vault's list could not be read right now — unreachable storage, for instance. */
    data object IndexUnavailable :
        VaultTrashFailure("the vault's list cannot be reached")

    /**
     * The vault's list does not name the item, so there is nothing to move to trash.
     *
     * Reported rather than treated as a no-op: the file the caller asked about is not in the vault, and
     * saying "done" would claim a file was moved that does not exist.
     */
    data object ItemNotInVault :
        VaultTrashFailure("the item is not in this vault")

    /** The vault holds as many trashed items as one record can carry. */
    data object TrashFull :
        VaultTrashFailure("the vault cannot hold another trashed item")

    /** The vault root cannot be reached, so the record could not be written. */
    data object StorageUnavailable :
        VaultTrashFailure("the vault's storage cannot be reached")

    /** The platform refused a write while the record was being written. */
    data object WriteFailed :
        VaultTrashFailure("the vault's storage refused a write")

    /**
     * Something written did not come back as it was written.
     *
     * The record was read again after writing and did not match what was intended: Nivara reports a
     * failure rather than claiming an item is in the trash when the bytes on storage say otherwise.
     */
    data object VerificationFailed :
        VaultTrashFailure("what was written did not verify")

    /** The platform key that protects the vault's key material is gone or unusable. */
    data object KeyUnavailable :
        VaultTrashFailure("the platform key protecting the vault is not available")

    /** One of the existing cryptographic services refused the operation. */
    data object CryptographyFailed :
        VaultTrashFailure("a cryptographic service refused the operation")
}

/**
 * The vault's trash, and the two operations that change it.
 *
 * ### A state, not a place
 *
 * Trashing an item writes one line into one small authenticated record and touches nothing else. The
 * item keeps its identifier, its encrypted object stays exactly where it was, its metadata stays in
 * the index and its albums keep naming it. Restoring removes that line. Nothing in this interface can
 * delete content, delete metadata or consume storage: there is no operation here that could express
 * permanent deletion, which is exactly why there is none.
 *
 * ### Reads and writes
 *
 * [read] answers without changing anything and never creates a record. [trash] and [restore] are the
 * whole of what a person can do to their trash in this stage. Both are idempotent where that is
 * honest: an item already in the trash is not written twice, and an item already out of it is not
 * "restored" again.
 *
 * ### Authorization
 *
 * Every mutation takes an `authorize` function rather than a session: the policy belongs to the
 * caller (the existing session gate), and this layer only refuses to write when it is told no. It is
 * asked before anything is read and again before the new record is committed, so a session that ends
 * mid-operation cannot produce a change that was never authorized.
 */
interface VaultTrashRepository {

    /**
     * Reports what the vault's trash holds.
     *
     * Reads only: a record that cannot be read is reported as such and left exactly as it was found,
     * never rebuilt, never repaired and never replaced with an empty one.
     */
    suspend fun read(): VaultTrashState

    /**
     * Moves the item [itemId] out of the active collection.
     *
     * The item must be one the vault's readable index names: an operation that could move an item the
     * vault does not hold would be inventing one. The item's identifier, metadata, encrypted object and
     * album memberships are untouched — this writes one line into the trash record and nothing else.
     *
     * Idempotent: an item already in the trash leaves the record as it was and is reported as a
     * success, because what the person asked for is true either way. Nothing is reported as done until
     * the new record has been read back and verified.
     *
     * @return the entry as it was committed, so a screen can show it without a second read.
     */
    suspend fun trash(
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultTrashEntry>

    /**
     * Moves the item [itemId] back into the active collection.
     *
     * The same identifier, the same metadata, the same encrypted object and the same album memberships
     * it had before: restoring removes one line from the trash record. It does not claim the item's
     * content is present — if the encrypted object is missing, the vault's list says so and opening the
     * file reports exactly what it reported before it was trashed.
     *
     * Idempotent: an item that is not in the trash leaves the record as it was and is reported as a
     * success.
     */
    suspend fun restore(
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<Unit>
}

/**
 * What the vault's index says about one trashed item.
 *
 * The three cases are the three different facts a trash row can be in, and they are not
 * interchangeable: [InVault] means the file is still in the vault and its details can be shown,
 * [NoLongerInVault] means the index was read and does not name it (the entry is kept and shown as such,
 * never quietly dropped), and [VaultListUnreadable] means there is nothing to join against — which must
 * not be drawn as the second case, because "the vault does not have this file" and "Nivara cannot read
 * the vault's list" are different facts about a person's files.
 */
enum class VaultTrashItemStatus {

    /** The index was read and names the item. Its details are available. */
    InVault,

    /** The index was read and does not name the item. The reference is kept and shown as missing. */
    NoLongerInVault,

    /** The index cannot be read, so nothing about the item can be said right now. */
    VaultListUnreadable,
}

/**
 * One trashed item, joined with whatever the vault's index says about it.
 *
 * @property entry the reference and the moment, as the trash record holds them.
 * @property item the item's authenticated metadata, or `null` when the index does not name it or could
 *   not be read.
 * @property listReadable whether the index was readable when this join was made. With [item] this
 *   distinguishes a stale reference from an unreadable list.
 */
data class VaultTrashItem(
    val entry: VaultTrashEntry,
    val item: VaultItem?,
    val listReadable: Boolean,
) {

    /** The identifier of the trashed item. */
    val itemId: VaultItemId get() = entry.itemId

    /** When the item left the active collection. */
    val trashedAtEpochMillis: Long get() = entry.trashedAtEpochMillis

    /** Which of the three facts above holds for this entry. */
    val status: VaultTrashItemStatus
        get() = when {
            item != null -> VaultTrashItemStatus.InVault
            listReadable -> VaultTrashItemStatus.NoLongerInVault
            else -> VaultTrashItemStatus.VaultListUnreadable
        }
}

/**
 * Joins every trashed entry with the vault's index.
 *
 * A pure join, kept in the domain so it can be tested without a device, a screen or a repository, and
 * so the rule it encodes is in one place: an identifier the index does not name is *kept* and reported
 * as missing, and an index that cannot be read leaves every entry unresolved rather than looking
 * stale. Nothing here removes, repairs or rewrites anything.
 */
fun List<VaultTrashEntry>.resolveAgainst(index: VaultIndexState): List<VaultTrashItem> =
    map { entry -> entry.resolveAgainst(index) }

/** Resolves one trashed entry against the vault's index, exactly as [resolveAgainst] does for a list. */
fun VaultTrashEntry.resolveAgainst(index: VaultIndexState): VaultTrashItem {
    val readable = index as? VaultIndexState.Ready
    if (readable == null) return VaultTrashItem(entry = this, item = null, listReadable = false)
    val item = readable.items.firstOrNull { candidate -> candidate.id == itemId }
    return VaultTrashItem(entry = this, item = item, listReadable = true)
}

/**
 * The failure behind a failed trash result, when it is one of Nivara's own.
 *
 * A result carries an [Exception] for diagnostics; this reads the typed failure back out of it so a
 * caller can map it to a screen without matching on exception classes of its own.
 */
fun Throwable?.asTrashFailure(): VaultTrashFailure? = this as? VaultTrashFailure
