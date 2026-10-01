package com.nivara.app.ui.vault

import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultOrdering
import com.nivara.app.domain.vault.VaultTrashOrdering
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadableReason
import com.nivara.app.ui.components.NivaraMessage

/**
 * State of the vault screen.
 *
 * The screen has one thing to show — [VaultState], which says exactly what is at the root the user
 * selected — and three things to say about the moment: whether a change is running, whether the user
 * has to authenticate first, and what the last action's outcome was.
 *
 * Keeping the vault's state whole rather than flattening it is the point of the whole design: the
 * screen renders "no vault here" and "a vault I cannot open" as different things because the state
 * says different things, and no path in the view model can turn either into an empty vault.
 *
 * ### What is deliberately not here
 *
 * No key material, no unwrapped content key, no wrapped blob, no storage reference. The screen says
 * where the vault stands; it never holds anything that could open it, which is why nothing here can
 * end up in a Compose state, a saved instance or a screenshot.
 */
sealed interface VaultUiState {

    /** The first look at the root has not finished. */
    data object Loading : VaultUiState

    /**
     * The vault's state, and the controls that are meaningful for it.
     *
     * @param vault what is at the selected root, in the domain's own words.
     * @param index the list of files the vault holds, filtered by [searchQuery] and ordered by
     *   [ordering] — the same authenticated metadata, arranged the way the screen is currently
     *   showing it.
     * @param organization the albums, resolved against the same index.
     * @param section which collection the screen is showing: every file, or the albums.
     * @param searchQuery what is typed in the search box, as typed. The screen draws it back; the
     *   filter itself is applied to [index] and to the open album.
     * @param search what the query currently means, so "no file matches" is never drawn for a list
     *   that could not be read.
     * @param ordering the chosen field and direction, and the only thing that decides how two items
     *   compare.
     * @param openAlbum the album being shown, when one is open.
     * @param renamingAlbumId the album whose title is being edited, when one is.
     * @param confirmingAlbumDeleteId the album whose deletion is being confirmed, when one is.
     * @param editingAlbumItems whether the open album is showing its add/remove surface.
     * @param sessionAuthenticated whether Nivara currently has a valid session. Creating a vault and
     *   every album change are durable configuration changes and are refused while this is `false`.
     * @param busy `true` while a change is being applied; every control is disabled meanwhile.
     * @param unlockRequired `true` when the user asked for a change and has no session. The screen
     *   answers this by sending the user to the existing credential screen.
     * @param failure why the last action failed, when it did. Generic copy only.
     * @param noticeRes a short confirmation for the last action, when it succeeded.
     */
    data class Ready(
        val vault: VaultState,
        val index: VaultIndexUiState = VaultIndexUiState.Loading,
        val organization: VaultOrganizationUiState = VaultOrganizationUiState.Loading,
        val trash: VaultTrashUiState = VaultTrashUiState.Loading,
        val section: VaultSection = VaultSection.AllItems,
        val searchQuery: String = "",
        val search: VaultSearchUiState = VaultSearchUiState.NotAsked,
        val searchSummary: VaultSearchSummary? = null,
        val ordering: VaultOrdering = VaultOrdering(),
        val trashOrdering: VaultTrashOrdering = VaultTrashOrdering(),
        /**
         * Whether the files drawn below may include ones that are out of the active collection,
         * because the trash record could not be read.
         *
         * `false` whenever the trash record answered — including when it says nothing is trashed —
         * and `true` only when Nivara cannot say which files are out of sight. The screen says so
         * instead of letting a list that may contain trashed files look like the active collection.
         */
        val trashStateUnknown: Boolean = false,
        val openAlbum: VaultAlbumDetailUi? = null,
        val renamingAlbumId: VaultAlbumId? = null,
        val confirmingAlbumDeleteId: VaultAlbumId? = null,
        val editingAlbumItems: Boolean = false,
        val sessionAuthenticated: Boolean,
        val busy: Boolean = false,
        val importing: Boolean = false,
        val progress: VaultImportProgress? = null,
        val unlockRequired: Boolean = false,
        val failure: NivaraMessage? = null,
        val noticeRes: Int? = null,
    ) : VaultUiState {

        /**
         * Whether the screen should offer to import a file.
         *
         * Everything that has to be true at once: the vault can be opened, its list can be read (or
         * does not exist yet), no other change is running, and the gate is open. An import is a
         * durable change to the vault, so it is offered under exactly the session the rest of the
         * screen's changes use.
         */
        val canImport: Boolean
            get() = !busy && !importing && sessionAuthenticated &&
                vault is VaultState.Ready && index.acceptsImport

        /**
         * Whether the root is one a vault may be created at — a fact about the storage alone.
         *
         * True when the root holds nothing but Nivara's own structure, so creating a vault destroys
         * nothing: no vault at all, or an unfinished initialization that left the structure behind and
         * no record with it. A root holding a record Nivara could not open is *not* included, because
         * replacing one is a destructive act with its own explicit control.
         */
        val vaultCanBeInitialized: Boolean
            get() = when (vault) {
                VaultState.Missing -> true
                is VaultState.Unreadable -> vault.reason == VaultUnreadableReason.StructureIncomplete
                else -> false
            }

        /**
         * Whether the root holds records Nivara could not open — again a fact about the storage.
         *
         * A lost platform key is included: the record exists and may still hold recoverable material,
         * so only the user can decide to give it up. A vault written by a newer Nivara is not: those
         * records are readable and merely unknown, and a later version can still open them.
         */
        val vaultHasUnreadableRecords: Boolean
            get() = vault is VaultState.Unreadable && vault.reason != VaultUnreadableReason.StructureIncomplete

        /**
         * Whether the screen should offer to create a vault right now.
         *
         * The storage fact, the session, and the absence of a change already running — all three.
         */
        val canInitialize: Boolean
            get() = !busy && sessionAuthenticated && vaultCanBeInitialized

        /**
         * Whether the screen should offer the explicit destructive replacement.
         *
         * Only while the gate is open and no change is running, and only for records Nivara could not
         * open at all.
         */
        val canReplaceUnreadable: Boolean
            get() = !busy && sessionAuthenticated && vaultHasUnreadableRecords
    }
}
