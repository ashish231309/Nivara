package com.nivara.app.ui.vault

import com.nivara.app.domain.vault.VaultAlbum
import com.nivara.app.domain.vault.VaultAlbumContents
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultOrganizationState
import com.nivara.app.domain.vault.VaultOrganizationUnreadable
import com.nivara.app.domain.vault.defaultAlbumOrder

/**
 * What the vault screen knows about the albums.
 *
 * The organisation record is drawn as its own fact, next to the vault's state and the index's, for the
 * same reason those two are kept apart: an album record that cannot be read is not a vault without
 * albums, and drawing it as one would invite a person to start organising again over the top of
 * albums that are still on storage.
 *
 * [Empty] is therefore the ordinary state of a vault nobody has organised yet, and it is deliberately
 * a different value from every unreadable state below it.
 */
sealed interface VaultOrganizationUiState {

    /** The album record has not been read yet. */
    data object Loading : VaultOrganizationUiState

    /** The vault itself is not ready, so there is nothing to organise yet. Its own card says why. */
    data object VaultNotReady : VaultOrganizationUiState

    /** A readable record with no albums in it: nobody has created one in this vault. */
    data object Empty : VaultOrganizationUiState

    /**
     * The albums, in the order they are shown.
     *
     * @param albums the albums, already sorted and already resolved against whatever the index says,
     *   so a row can say how many of its items are still in the vault.
     */
    data class Albums(val albums: List<VaultAlbumUi>) : VaultOrganizationUiState

    /** The record exists and cannot be read. Nothing is created, renamed or deleted while this holds. */
    data class Unreadable(val reason: VaultOrganizationUnreadable) : VaultOrganizationUiState

    /** The record was written by a newer Nivara. It is never written over. */
    data object UnsupportedVersion : VaultOrganizationUiState

    /** The record could not be read right now — unreachable storage, for instance. */
    data object Unavailable : VaultOrganizationUiState

    /** The platform no longer grants access to the vault. */
    data object AccessDenied : VaultOrganizationUiState

    /**
     * Whether an album may be created or changed from this state.
     *
     * Only a readable record, or the certain knowledge that none exists yet. Every other state means
     * the albums Nivara cannot see are still there, and writing over them is how they would be lost.
     */
    val acceptsChanges: Boolean
        get() = this is Empty || this is Albums
}

/**
 * One album as the album list draws it.
 *
 * The counts are resolved against the index when it can be read: [staleCount] is how many of the
 * album's items the index no longer names. When the index cannot be read the counts still describe the
 * album record itself — how many references it holds — and [resolved] is `false`, which is the
 * difference between "this album holds three items" and "this album holds three references and Nivara
 * cannot say whether they are still there".
 */
data class VaultAlbumUi(
    val id: VaultAlbumId,
    val name: String,
    val createdAtEpochMillis: Long,
    val memberCount: Int,
    val staleCount: Int,
    val resolved: Boolean,
    /**
     * How many of the album's members are in the vault's trash.
     *
     * The membership is preserved — restoring the item puts it back in the album's drawn contents —
     * but a trashed file is not active content, so it is counted apart rather than drawn as one.
     */
    val trashedCount: Int = 0,
)

/**
 * What an open album shows.
 *
 * The album's items are drawn exactly like the vault's own list, because they are the same rows of the
 * same list: the same name, the same kind, the same size. Selecting one opens it through the one
 * viewer the vault has, whichever collection it was reached from.
 */
data class VaultAlbumDetailUi(
    val album: VaultAlbumUi,
    val contents: VaultAlbumContentsUi,
    /**
     * Every item reference the album holds — the valid ones and the stale ones alike.
     *
     * This comes from the album record, which is the only thing that decides membership; it is not
     * derived from the drawn list, so a search that filters a row out of view cannot make an item look
     * as if it were in no album. The add/remove surface asks this set, never a filtered list.
     */
    val memberItemIds: Set<VaultItemId>,
)

/** An open album's membership, resolved or not. */
sealed interface VaultAlbumContentsUi {

    /** The album was resolved against a readable index. */
    data class Resolved(
        val items: List<VaultItemUi>,
        val staleItemIds: List<VaultItemId>,
        val trashedItemIds: List<VaultItemId> = emptyList(),
    ) : VaultAlbumContentsUi

    /** The index cannot be read, so the album's references cannot be resolved into files. */
    data class Unresolved(val index: VaultIndexUiState) : VaultAlbumContentsUi
}

/** Which collection the vault screen is showing. */
enum class VaultSection {

    /** Every file the vault holds. */
    AllItems,

    /** The albums, and the one that is open when there is one. */
    Albums,

    /**
     * The files the vault has moved out of the active collection.
     *
     * Its own collection, and not a filter over the first one: what is in the trash is metadata the
     * index does not carry, and a person looking at their trash is asking a different question than a
     * person looking at their files.
     */
    Trash,
}

/** Turns what the repository read into what the screen draws, without inventing anything. */
internal fun VaultOrganizationState.toUiState(
    contents: (VaultAlbum) -> VaultAlbumContents,
): VaultOrganizationUiState = when (this) {
    VaultOrganizationState.Missing -> VaultOrganizationUiState.Empty

    is VaultOrganizationState.Ready -> VaultOrganizationUiState.Albums(
        albums = albums
            .sortedWith(defaultAlbumOrder)
            .map { album -> album.toAlbumUi(contents(album)) },
    )

    is VaultOrganizationState.Unreadable -> VaultOrganizationUiState.Unreadable(reason)
    is VaultOrganizationState.UnsupportedVersion -> VaultOrganizationUiState.UnsupportedVersion
    VaultOrganizationState.Unavailable -> VaultOrganizationUiState.Unavailable
    VaultOrganizationState.AccessDenied -> VaultOrganizationUiState.AccessDenied
    is VaultOrganizationState.VaultNotReady -> VaultOrganizationUiState.VaultNotReady
}

/**
 * One album as a row: its title, when it was made, and what it holds.
 *
 * A stale count is only known when the index could be read — the album record itself cannot tell
 * whether an item it names is still in the vault — so an unresolved album reports `staleCount = 0`
 * *and* `resolved = false`, and the screen says which of the two it is looking at rather than showing
 * a count it does not have.
 */
internal fun VaultAlbumContents.toUiState(): VaultAlbumContentsUi = when (this) {
    is VaultAlbumContents.Resolved -> VaultAlbumContentsUi.Resolved(
        // Every item of an album is drawn exactly as the vault's own list draws it: resolved from the
        // index, never from a copy kept in the album.
        items = items.map { item -> item.toItemUi() },
        staleItemIds = staleItemIds,
        trashedItemIds = trashedItemIds,
    )

    is VaultAlbumContents.Unresolved -> VaultAlbumContentsUi.Unresolved(index = index.toUiState())
}

/** One album as a row, from the album record and what resolving it against the index found. */
internal fun VaultAlbum.toAlbumUi(contents: VaultAlbumContents): VaultAlbumUi = when (contents) {
    is VaultAlbumContents.Resolved -> VaultAlbumUi(
        id = id,
        name = name,
        createdAtEpochMillis = createdAtEpochMillis,
        memberCount = contents.items.size + contents.staleItemIds.size + contents.trashedItemIds.size,
        staleCount = contents.staleItemIds.size,
        resolved = true,
        trashedCount = contents.trashedItemIds.size,
    )

    is VaultAlbumContents.Unresolved -> VaultAlbumUi(
        id = id,
        name = name,
        createdAtEpochMillis = createdAtEpochMillis,
        memberCount = itemIds.size,
        staleCount = 0,
        resolved = false,
    )
}
