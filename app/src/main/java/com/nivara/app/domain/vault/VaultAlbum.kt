package com.nivara.app.domain.vault

/**
 * One album: a title a person chose, and the items they put in it.
 *
 * ### What an album is, and what it is not
 *
 * An album is *organisation*, not storage. It holds [itemIds] — references to items the vault's own
 * authenticated index names — and nothing else about them: no name, no type, no size, no digest and
 * no content location. A list that copied an item's facts into an album would be a second copy of the
 * index, and the first import that renamed something would leave the two disagreeing. Anything a
 * screen wants to say about an item in an album is read from the index when it is shown.
 *
 * An item may be in no albums, one album or several: belonging to an album says nothing about the
 * item, so there is no rule that would make a second membership impossible. Albums are not folders,
 * and nothing about an item's encrypted object changes when it is put in one.
 *
 * ### Membership is ordered and duplicate-free
 *
 * [itemIds] is the order the items were added, which is the only order an album has — the display
 * order a screen uses is the vault's sorting rule, applied on top. Membership is a set with an
 * order rather than a list with repeats: adding an item that is already in the album changes nothing
 * (see [adding]), and the codec refuses a stored record that names one item twice, because a
 * duplicate could only come from a writer that disagreed with itself.
 *
 * ### The title
 *
 * [name] is a label and never an identity: [id] is the album, the name is what is written on it. A
 * name is validated by [VaultAlbumNames] and may be changed at any time without touching membership.
 *
 * @property id the stable identifier, created once and never reused.
 * @property name the title, as the person typed it (trimmed and whitespace-collapsed).
 * @property createdAtEpochMillis when the album was created, in milliseconds since the epoch. Kept
 *   because it is genuinely useful — an album list has to break ties somehow — and never used as a
 *   security decision.
 * @property itemIds the items in the album, in the order they were added, each at most once.
 */
data class VaultAlbum(
    val id: VaultAlbumId,
    val name: String,
    val createdAtEpochMillis: Long,
    val itemIds: List<VaultItemId>,
) {

    init {
        require(VaultAlbumNames.isWellFormed(name)) { "an album name is usable text of a bounded length" }
        require(createdAtEpochMillis >= 0) { "an album creation time is an instant after the epoch" }
        require(itemIds.size <= VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM) {
            "an album holds at most ${VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM} items"
        }
        require(itemIds.distinct().size == itemIds.size) { "an album names an item at most once" }
    }

    /** How many items the album holds. */
    val size: Int get() = itemIds.size

    /** Whether [itemId] is already in the album. */
    fun contains(itemId: VaultItemId): Boolean = itemIds.contains(itemId)

    /**
     * The same album with [itemId] added at the end, or this album unchanged when it is already
     * there.
     *
     * Idempotent on purpose: tapping "add to album" twice is a user mistake, not a second membership,
     * and there is nothing an album could mean by naming one item twice.
     */
    fun adding(itemId: VaultItemId): VaultAlbum =
        if (contains(itemId)) this else copy(itemIds = itemIds + itemId)

    /**
     * The same album without [itemId], or this album unchanged when it was not there.
     *
     * Removing an item from an album says nothing about the item: it is still in the vault, its
     * encrypted object is untouched and it keeps its place in every other album it is in. Only this
     * album forgets it.
     */
    fun removing(itemId: VaultItemId): VaultAlbum =
        if (!contains(itemId)) this else copy(itemIds = itemIds.filterNot { id -> id == itemId })

    /** The same album with a new title. Membership is untouched, and [VaultAlbumNames] is enforced. */
    fun renamedTo(name: String): VaultAlbum? {
        val normalized = VaultAlbumNames.normalize(name) ?: return null
        return copy(name = normalized)
    }
}

/**
 * What one album holds, resolved against the vault's authenticated index.
 *
 * ### Why resolution is its own type
 *
 * An album names items by identifier; what an item *is* lives in the index. So showing an album means
 * joining two authenticated records, and the join can come out three ways: an item is in both (valid),
 * an item is in the album but not in the index (stale — the reference is kept and shown as such,
 * never quietly dropped), or the index could not be read at all, in which case there is nothing to
 * join against and every album looks empty for a reason that must be said out loud rather than
 * drawn as emptiness.
 */
sealed interface VaultAlbumContents {

    /** The album these contents are about. */
    val album: VaultAlbum

    /**
     * The album was resolved against a readable index.
     *
     * @param items the album's items, in album order, with their facts read from the index.
     * @param staleItemIds ids the album names that the index does not: kept, shown as missing, and
     *   removed only by an explicit organisation action.
     */
    data class Resolved(
        override val album: VaultAlbum,
        val items: List<VaultItem>,
        val staleItemIds: List<VaultItemId>,
    ) : VaultAlbumContents {

        /** Whether the album holds nothing at all — which is a valid, deliberately-created state. */
        val isEmpty: Boolean get() = items.isEmpty() && staleItemIds.isEmpty()
    }

    /**
     * The index cannot be read right now, so the album's references cannot be resolved.
     *
     * The [index] state travels with it so a screen says the index's own reason — damaged, a newer
     * format, unreachable storage, the vault itself not ready — instead of inventing one.
     */
    data class Unresolved(
        override val album: VaultAlbum,
        val index: VaultIndexState,
    ) : VaultAlbumContents
}

/**
 * Resolves every album against the vault's index.
 *
 * A pure join, kept in the domain so it can be tested without a device, a screen or a repository, and
 * so the rule it encodes is in one place: an identifier the index does not name is *kept* and reported
 * as stale. Nothing here removes, repairs or rewrites anything — the only thing that removes a stale
 * reference is a person asking to.
 */
fun List<VaultAlbum>.resolveAgainst(index: VaultIndexState): List<VaultAlbumContents> =
    map { album -> album.resolveAgainst(index) }

/** Resolves one album against the vault's index, exactly as [resolveAgainst] does for a list. */
fun VaultAlbum.resolveAgainst(index: VaultIndexState): VaultAlbumContents {
    val readable = index as? VaultIndexState.Ready
        ?: return VaultAlbumContents.Unresolved(album = this, index = index)
    val byId = readable.items.associateBy { item -> item.id }
    val items = ArrayList<VaultItem>(itemIds.size)
    val stale = ArrayList<VaultItemId>()
    for (itemId in itemIds) {
        val item = byId[itemId]
        if (item == null) stale += itemId else items += item
    }
    return VaultAlbumContents.Resolved(album = this, items = items, staleItemIds = stale)
}

/**
 * The limits the organisation record obeys.
 *
 * Every one of them exists so that a stored record can be decoded without trusting it: a count read
 * from untrusted bytes is a *claim*, and these are the bounds that make the claim checkable before
 * anything is allocated from it. They are also the limits the writing side refuses to exceed, so a
 * record Nivara writes is always one Nivara can read back.
 */
object VaultOrganizationLimits {

    /**
     * The most albums one vault may hold.
     *
     * A bound of the format rather than of the product: the count is written in two bytes, and the
     * whole record has to fit inside [MAXIMUM_RECORD_BYTES]. A vault that one day needs more than
     * this raises the bound with a new format version rather than growing the record past what it
     * promises to read.
     */
    const val MAXIMUM_ALBUMS: Int = 2_000

    /** The most items one album may hold. */
    const val MAXIMUM_MEMBERS_PER_ALBUM: Int = 5_000

    /**
     * The most membership references the whole record may hold, across every album.
     *
     * This is the bound that actually limits the record: albums share items, so a per-album limit
     * alone would let a thousand albums of a thousand items describe a record far larger than the
     * decoder will ever read. Checked before allocation, from the encoded sizes.
     */
    const val MAXIMUM_TOTAL_MEMBERSHIPS: Int = 50_000

    /** The longest album name, in encoded UTF-8 bytes. */
    const val MAXIMUM_NAME_BYTES: Int = VaultAlbumNames.MAXIMUM_LENGTH * 4

    /** The largest organisation record Nivara will read, sealing included. */
    const val MAXIMUM_RECORD_BYTES: Int = 4 * 1024 * 1024
}
