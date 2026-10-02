package com.nivara.app.ui.vault

import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultSearch
import com.nivara.app.domain.vault.VaultSearchQuery
import com.nivara.app.domain.vault.VaultTrashItem
import com.nivara.app.domain.vault.VaultTrashItemStatus
import com.nivara.app.domain.vault.VaultTrashOrdering
import com.nivara.app.domain.vault.VaultTrashState
import com.nivara.app.domain.vault.VaultTrashUnreadable
import com.nivara.app.domain.vault.inTrashOrder
import com.nivara.app.domain.vault.resolveAgainst

/**
 * What the vault screen knows about the trash.
 *
 * The trash record is drawn as its own fact, next to the vault's state, the index's and the albums',
 * for the same reason those are kept apart: a record that cannot be read is not an empty trash, and
 * drawing it as one would tell somebody their files are gone when they are still in the vault — the
 * one misreading this feature exists to prevent.
 *
 * [Empty] is therefore the ordinary state of a vault nobody has moved anything out of, and it is
 * deliberately a different value from every unreadable state below it.
 */
sealed interface VaultTrashUiState {

    /** The trash record has not been read yet. */
    data object Loading : VaultTrashUiState

    /** The vault itself is not ready, so there is no trash to speak of. Its own card says why. */
    data object VaultNotReady : VaultTrashUiState

    /** A readable record with no entries: nothing has been moved out of the active collection. */
    data object Empty : VaultTrashUiState

    /**
     * The trashed items, in the order they are shown.
     *
     * Each row is the trash record joined with whatever the vault's list says about the item, so a row
     * can say whether the file is still in the vault, whether the list no longer names it, or whether
     * the list could not be read at all.
     */
    data class Trashed(val items: List<VaultTrashItemUi>) : VaultTrashUiState

    /** The record exists and cannot be read. Nothing is moved in or out of the trash while this holds. */
    data class Unreadable(val reason: VaultTrashUnreadable) : VaultTrashUiState

    /** The record was written by a newer Nivara. It is never written over. */
    data object UnsupportedVersion : VaultTrashUiState

    /** The record could not be read right now — unreachable storage, for instance. */
    data object Unavailable : VaultTrashUiState

    /** The platform no longer grants access to the vault. */
    data object AccessDenied : VaultTrashUiState

    /**
     * Whether the trash may be changed from this state.
     *
     * Only a readable record, or the certain knowledge that none exists yet. Every other state means
     * the entries Nivara cannot see are still there, and writing over them is how they would be lost.
     */
    val acceptsChanges: Boolean
        get() = this is Empty || this is Trashed
}

/**
 * One trashed item as the trash list draws it.
 *
 * The details come from the vault's authenticated index, never from the trash record: an entry holds an
 * identifier and a moment, and nothing else. When the index does not name the item — or cannot be asked
 * — [item] is `null` and [status] says which of those two it is, because "this file is no longer in the
 * vault" and "the vault's list cannot be read" are different facts about somebody's files.
 *
 * @property id the item's identifier. It is what a restore names, and it is not displayed.
 * @property trashedAtEpochMillis when the item left the active collection.
 * @property item the item's metadata as the vault's list holds it, or `null` when it cannot be shown.
 * @property status whether the item is in the vault, is no longer named by its list, or could not be
 *   resolved because the list cannot be read.
 * @property contentMissing whether the list says the item's encrypted content is missing. Meaningful
 *   only when [item] is present; a row whose content is missing says so rather than offering a promise
 *   it cannot keep.
 */
data class VaultTrashItemUi(
    val id: VaultItemId,
    val trashedAtEpochMillis: Long,
    val item: VaultItemUi?,
    val status: VaultTrashItemStatus,
    val contentMissing: Boolean,
)

/**
 * Turns what the repository read into what the screen draws, without inventing anything.
 *
 * The entries are joined with the list that was already read, filtered by the same search box and
 * ordered by the trash's own ordering — all in memory, from metadata the vault has already
 * authenticated. Nothing here reads a vault, a key or a file.
 */
internal fun VaultTrashState.toUiState(
    index: VaultIndexState,
    ordering: VaultTrashOrdering,
    query: VaultSearchQuery,
): VaultTrashUiState = when (this) {
    VaultTrashState.Missing -> VaultTrashUiState.Empty

    is VaultTrashState.Ready -> VaultTrashUiState.Trashed(
        items = entries
            .resolveAgainst(index)
            .let { resolved -> VaultSearch.filterTrash(resolved, query) }
            .inTrashOrder(ordering)
            .map { item -> item.toUiState(missingContentOf(index)) },
    )

    is VaultTrashState.Unreadable -> VaultTrashUiState.Unreadable(reason)
    is VaultTrashState.UnsupportedVersion -> VaultTrashUiState.UnsupportedVersion
    VaultTrashState.Unavailable -> VaultTrashUiState.Unavailable
    VaultTrashState.AccessDenied -> VaultTrashUiState.AccessDenied
    is VaultTrashState.VaultNotReady -> VaultTrashUiState.VaultNotReady
}

/** The ids the vault's list says have no encrypted content, when the list could be read. */
private fun missingContentOf(index: VaultIndexState): Set<VaultItemId> =
    (index as? VaultIndexState.Ready)?.missingContent ?: emptySet()

/**
 * One trashed item as a row: the item's own details when the list holds them, and the reason they are
 * absent when it does not.
 */
internal fun VaultTrashItem.toUiState(missingContent: Set<VaultItemId>): VaultTrashItemUi =
    VaultTrashItemUi(
        id = itemId,
        trashedAtEpochMillis = trashedAtEpochMillis,
        item = item?.toItemUi(),
        status = status,
        contentMissing = item != null && itemId in missingContent,
    )
