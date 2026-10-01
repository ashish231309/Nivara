package com.nivara.app.domain.vault

/**
 * The order a vault's files are shown in.
 *
 * ### Deterministic, and deliberately dull
 *
 * Every order here is a total order over the items in the vault: the comparison always ends in the
 * item's identifier, so two items that are identical in every visible way still come out in a fixed
 * sequence, and the same vault always produces the same list. Nothing in this file is a ranking.
 * There is no "most opened", no "recently viewed" and no per-device weighting, because Nivara keeps
 * no usage history — a list that changes for reasons its owner cannot see is worse than a plain
 * alphabetical one, and inventing a ranking would mean collecting the history to justify it.
 *
 * ### Why the identifier breaks ties
 *
 * Any two files may share a name, a size and an import instant — importing the same photo twice in
 * one second produces exactly that — so a comparison that stopped at the visible fields would leave
 * their order to whatever the list happened to be in. Ending in the identifier makes the order total,
 * which is what "the same vault always produces the same list" actually requires.
 */
enum class VaultSortField {

    /** The file's name, compared case-insensitively. */
    Name,

    /** The file's size in bytes. */
    Size,

    /** When the file was imported. */
    ImportedAt,

    /** The file's classified kind — image, video, audio, document, other. */
    Kind,
}

/** Which way an order runs. Direction is the only thing this names; the comparisons live above. */
enum class VaultSortDirection {

    Ascending,
    Descending,
    ;

    /** The opposite direction. */
    fun reversed(): VaultSortDirection =
        if (this == Ascending) Descending else Ascending
}

/**
 * One field and one direction, resolved into a comparison.
 *
 * This is the single place that decides how two vault items compare. A screen offers the choice; the
 * rule that implements it lives here, so two screens cannot disagree about what "by size" means.
 */
data class VaultOrdering(
    val field: VaultSortField = DEFAULT_FIELD,
    val direction: VaultSortDirection = DEFAULT_DIRECTION,
) {

    /** The comparison this ordering describes, ending in the item identifier so it is total. */
    val comparator: Comparator<VaultItem> get() = orderedByFieldThenIdentifier()

    /**
     * The comparison itself, as a function rather than a getter body.
     *
     * The type argument is written out on every `compareBy` because the field it compares is known
     * only from the enum branch it belongs to — the compiler has nothing else to infer from — and the
     * tie-breaker at the end needs no argument because the receiver already fixes it.
     */
    private fun orderedByFieldThenIdentifier(): Comparator<VaultItem> {
        val primary: Comparator<VaultItem> = when (field) {
            VaultSortField.Name -> compareBy<VaultItem> { item -> item.name.lowercase() }
                .thenBy<VaultItem> { item -> item.name }

            VaultSortField.Size -> compareBy<VaultItem> { item -> item.sizeBytes }
            VaultSortField.ImportedAt -> compareBy<VaultItem> { item -> item.importedAtEpochMillis }
            VaultSortField.Kind -> compareBy<VaultItem> { item -> kindRank(item) }
                .thenBy<VaultItem> { item -> item.name.lowercase() }
        }
        val total = primary.thenBy { item -> item.id.value }
        return if (direction == VaultSortDirection.Ascending) total else total.reversed()
    }

    /** The same field, the other direction. */
    fun toggled(): VaultOrdering = copy(direction = direction.reversed())

    companion object {

        /**
         * The default order: newest first.
         *
         * This is the order the vault has shown since it first listed anything, and it is kept: a file
         * a person has just imported is the one they are looking for. It is now total — the identifier
         * breaks ties between two imports in the same millisecond — which is the only change.
         */
        val DEFAULT_FIELD: VaultSortField = VaultSortField.ImportedAt
        val DEFAULT_DIRECTION: VaultSortDirection = VaultSortDirection.Descending
    }
}

/**
 * Where a content kind sorts.
 *
 * A rank rather than the enum's own order, because the order a person expects is the order the list
 * groups things in — pictures, then video, then audio, then documents, then everything else — and
 * that is a product decision rather than a property of the classifier. The classifier's own ordinal
 * would tie this file's meaning to the order its cases happen to be written in.
 */
private fun kindRank(item: VaultItem): Int = when (VaultContentClassification.kindOf(item.mimeType)) {
    VaultContentKind.Image -> 0
    VaultContentKind.Video -> 1
    VaultContentKind.Audio -> 2
    VaultContentKind.Document -> 3
    VaultContentKind.Other -> 4
}

/** Returns this list in the order [ordering] describes, leaving the receiver untouched. */
fun List<VaultItem>.inOrder(ordering: VaultOrdering): List<VaultItem> = sortedWith(ordering.comparator)

/**
 * The order albums are shown in: the title, compared case-insensitively, with the album identifier as
 * the tie-breaker.
 *
 * Exactly the same shape as the item ordering and for exactly the same reason: two albums may share a
 * title, and the one that was created first is not more important than the one that was created
 * second — so the identifier decides, the order is total, and the list never reshuffles itself
 * between one look and the next.
 */
val defaultAlbumOrder: Comparator<VaultAlbum> =
    compareBy<VaultAlbum>({ album -> album.name.lowercase() }, { album -> album.name })
        .thenBy { album -> album.id.value }

/** Returns these albums in [defaultAlbumOrder], leaving the receiver untouched. */
fun List<VaultAlbum>.inDefaultAlbumOrder(): List<VaultAlbum> = sortedWith(defaultAlbumOrder)
