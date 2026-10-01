package com.nivara.app.ui.vault

import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultContentClassification
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultItemId

/**
 * What the vault screen knows about the list of files in the vault.
 *
 * The index is drawn as its own fact, next to the vault's state, because the two can differ: a vault
 * can be perfectly intact while the list of what it holds cannot be read. Keeping them apart is what
 * stops "the list cannot be read" from being drawn as "the vault is empty" — the one misreading every
 * part of this feature is built to prevent.
 *
 * ### What is not here
 *
 * No identifier of an item, no key material, no platform reference and no bytes: the screen shows a
 * file's name, how large it is, when it arrived and a generic kind. That is everything a person needs
 * to recognise their own file, and nothing that could open it.
 */
sealed interface VaultIndexUiState {

    /** The list has not been read yet. */
    data object Loading : VaultIndexUiState

    /** The vault itself is not ready, so there is no list to speak of. The vault's own card says why. */
    data object VaultNotReady : VaultIndexUiState

    /** The vault holds no index record: nothing has been imported into it yet. */
    data object Empty : VaultIndexUiState

    /**
     * The list was read.
     *
     * @param items the imported files, newest first.
     * @param missingContent how many listed files have no encrypted content in the vault.
     * @param unindexedObjects how many encrypted files are in the content area without being listed,
     *   or `null` when the area could not be listed — never `0` for "could not look".
     * @param unfinishedObjects how many objects were left part-way through being written, or `null`
     *   when unknown.
     */
    data class Indexed(
        val items: List<VaultItemUi>,
        val missingContent: Int = 0,
        val unindexedObjects: Int? = null,
        val unfinishedObjects: Int? = null,
    ) : VaultIndexUiState {

        /** Whether a new file may be imported into this vault right now. */
        override val acceptsImport: Boolean get() = true
    }

    /** The list exists and cannot be read. Importing is refused while this is true. */
    data class Unreadable(val reason: VaultIndexUnreadable) : VaultIndexUiState

    /** The list was written by a newer Nivara. It is never written over. */
    data object UnsupportedVersion : VaultIndexUiState

    /** The list could not be read right now — unreachable storage, for instance. */
    data object Unavailable : VaultIndexUiState

    /** The platform no longer grants access to the vault. */
    data object AccessDenied : VaultIndexUiState

    /** Whether the vault's list is in a state that a new import may be added to. */
    val acceptsImport: Boolean
        get() = when (this) {
            Empty, is Indexed -> true
            Loading, VaultNotReady, is Unreadable, UnsupportedVersion, Unavailable, AccessDenied -> false
        }
}

/**
 * One imported file, as the list draws it.
 *
 * The name is the name the file had when it was imported, kept inside the authenticated index — it is
 * never a path and never the name of anything on storage, which is why showing it is safe.
 *
 * @property id the item's identifier, which is how the vault finds its encrypted object. It is not
 *   displayed: it is what a viewer is handed, and it is the one piece of an item that a screen needs
 *   in order to ask for the content behind it.
 */
data class VaultItemUi(
    val id: VaultItemId,
    val name: String,
    val kind: VaultContentKind,
    val sizeBytes: Long,
    val importedAtEpochMillis: Long,
    val mimeType: String?,
)

/**
 * One thing the screen has to say about a readable list besides the files themselves.
 *
 * The count travels with the wording because the sentence needs it ("2 earlier imports stopped…"),
 * and it is read from what the index and the content area actually say rather than guessed.
 */
internal data class VaultIndexNotice(val textRes: Int, val count: Int)

/** Turns what the repository read into what the screen draws, without inventing anything. */
internal fun VaultIndexState.toUiState(): VaultIndexUiState = when (this) {
    VaultIndexState.Missing -> VaultIndexUiState.Empty

    is VaultIndexState.Ready -> VaultIndexUiState.Indexed(
        items = items
            .sortedByDescending { item -> item.importedAtEpochMillis }
            .map { item -> item.toUi() },
        missingContent = missingContent.size,
        unindexedObjects = unindexedObjects,
        unfinishedObjects = unfinishedObjects,
    )

    is VaultIndexState.Unreadable -> VaultIndexUiState.Unreadable(reason)
    is VaultIndexState.UnsupportedVersion -> VaultIndexUiState.UnsupportedVersion
    VaultIndexState.Unavailable -> VaultIndexUiState.Unavailable
    VaultIndexState.AccessDenied -> VaultIndexUiState.AccessDenied
    is VaultIndexState.VaultNotReady -> VaultIndexUiState.VaultNotReady
}

private fun VaultItem.toUi(): VaultItemUi = VaultItemUi(
    id = id,
    name = name,
    // Classified once, here, from the authenticated type: the list, the viewer and the engine all
    // read this one answer rather than each deciding for itself.
    kind = VaultContentClassification.kindOf(mimeType),
    sizeBytes = sizeBytes,
    importedAtEpochMillis = importedAtEpochMillis,
    mimeType = mimeType,
)
