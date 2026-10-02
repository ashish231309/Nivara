package com.nivara.app.domain.vault

/**
 * The order the trash list is shown in.
 *
 * ### Deterministic, and deliberately dull
 *
 * Every order here is a total order over the trashed entries: the comparison always ends in the item's
 * identifier, so two files trashed in the same millisecond still come out in a fixed sequence. Nothing
 * in this file is a ranking. There is no "recently viewed", no usage and no per-device weighting,
 * because Nivara keeps no usage history — a list that changes for reasons its owner cannot see is
 * worse than a plain one.
 *
 * ### The fields
 *
 * [VaultTrashSortField.TrashedAt] is the one field the trash has that the vault's list does not: when
 * the file was moved out of the active collection. It is the default because it is the question a
 * person usually has about their trash — "what did I just move here?" — and it is a display order, not
 * a policy: nothing expires, nothing is deleted because of how long it has been there.
 *
 * The other four fields are the vault's own and compare the same way, using the metadata the index
 * still holds for the item. An entry the index cannot resolve has no name, size or import time to
 * compare, so unresolved entries sort after resolved ones and are ordered among themselves by
 * identifier — which is a fact the trash record itself holds.
 */
enum class VaultTrashSortField {

    /** When the file was moved out of the active collection. */
    TrashedAt,

    /** The file's name, compared case-insensitively. */
    Name,

    /** The file's size in bytes. */
    Size,

    /** When the file was imported into the vault. */
    ImportedAt,

    /** The file's classified kind — image, video, audio, document, other. */
    Kind,
}

/**
 * One field and one direction, resolved into a comparison over trashed entries.
 *
 * This is the single place that decides how two trashed items compare, so two screens cannot disagree
 * about what "by name" means. The comparison ends in the item identifier, which the trash record
 * guarantees is unique, so the order is total and the same trash always produces the same list.
 */
data class VaultTrashOrdering(
    val field: VaultTrashSortField = DEFAULT_FIELD,
    val direction: VaultSortDirection = DEFAULT_DIRECTION,
) {

    /** The comparison this ordering describes, ending in the item identifier so it is total. */
    val comparator: Comparator<VaultTrashItem> get() = orderedByFieldThenIdentifier()

    private fun orderedByFieldThenIdentifier(): Comparator<VaultTrashItem> {
        val primary: Comparator<VaultTrashItem> = when (field) {
            VaultTrashSortField.TrashedAt ->
                compareBy<VaultTrashItem> { item -> item.entry.trashedAtEpochMillis }

            VaultTrashSortField.Name ->
                compareBy<VaultTrashItem> { item -> if (item.item == null) 1 else 0 }
                    .thenBy<VaultTrashItem> { item -> item.item?.name?.lowercase() ?: "" }
                    .thenBy<VaultTrashItem> { item -> item.item?.name ?: "" }

            VaultTrashSortField.Size ->
                compareBy<VaultTrashItem> { item -> if (item.item == null) 1 else 0 }
                    .thenBy<VaultTrashItem> { item -> item.item?.sizeBytes ?: 0L }

            VaultTrashSortField.ImportedAt ->
                compareBy<VaultTrashItem> { item -> if (item.item == null) 1 else 0 }
                    .thenBy<VaultTrashItem> { item -> item.item?.importedAtEpochMillis ?: 0L }

            VaultTrashSortField.Kind ->
                compareBy<VaultTrashItem> { item -> if (item.item == null) 1 else 0 }
                    .thenBy<VaultTrashItem> { item -> trashKindRank(item.item?.mimeType) }
                    .thenBy<VaultTrashItem> { item -> item.item?.name?.lowercase() ?: "" }
        }
        val total = primary.thenBy { item -> item.entry.itemId.value }
        return if (direction == VaultSortDirection.Ascending) total else total.reversed()
    }

    /** The same field, the other direction. */
    fun toggled(): VaultTrashOrdering = copy(direction = direction.reversed())

    companion object {

        /**
         * The default order: the file moved to trash most recently, first.
         *
         * It is a display order and nothing more: nothing in Nivara expires, and no file is deleted
         * because of how long it has been in the trash.
         */
        val DEFAULT_FIELD: VaultTrashSortField = VaultTrashSortField.TrashedAt
        val DEFAULT_DIRECTION: VaultSortDirection = VaultSortDirection.Descending
    }
}

/**
 * Where a content kind sorts in the trash list.
 *
 * The same grouping the vault's own list uses — pictures, then video, then audio, then documents, then
 * everything else — read from the vault's one classifier rather than from a second set of rules
 * written for this screen.
 */
private fun trashKindRank(mimeType: String?): Int = when (VaultContentClassification.kindOf(mimeType)) {
    VaultContentKind.Image -> 0
    VaultContentKind.Video -> 1
    VaultContentKind.Audio -> 2
    VaultContentKind.Document -> 3
    VaultContentKind.Other -> 4
}

/** Returns this list in the order [ordering] describes, leaving the receiver untouched. */
fun List<VaultTrashItem>.inTrashOrder(ordering: VaultTrashOrdering): List<VaultTrashItem> =
    sortedWith(ordering.comparator)
