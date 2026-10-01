package com.nivara.app.ui.vault.viewer

import com.nivara.app.data.vault.viewer.VaultViewerFailure
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultItemId

/**
 * The item the viewer is showing, as facts rather than as content.
 *
 * A name, a declared type, a size, an arrival time and an opaque identifier: everything a screen may
 * say about a file, and nothing that could open it. The identifier is the one thing the vault needs
 * to find the encrypted object, and it is never displayed.
 */
internal data class VaultViewerItem(
    val id: VaultItemId,
    val name: String,
    val mimeType: String?,
    val kind: VaultContentKind,
    val sizeBytes: Long,
    val importedAtEpochMillis: Long,
)

/**
 * What the viewer is doing.
 *
 * ### One state at a time, and the differences between them matter
 *
 * [Unsupported], [Missing], [Unreadable], [Corrupt] and [Failed] are five different sentences, and
 * collapsing them would be the mistake the whole vault is built to avoid: a format this stage has no
 * viewer for is not corruption, a file the platform's decoder refuses is not a damaged vault, and an
 * item whose encrypted object is not there is not an empty vault.
 *
 * ### What is deliberately absent
 *
 * No bytes, no bitmap, no text, no player, no key: the states below are what a screen draws, and the
 * decoded content they describe is owned by the viewer for exactly as long as it is open. Nothing here
 * is ever written to a saved instance state — the item identifier is the only thing a screen would
 * even be able to keep, and the viewer is deliberately not restorable: a vault screen that came back
 * from a saved state is a screen that would have to re-open content it no longer has.
 */
internal sealed interface VaultViewerUiState {

    /** No item is open. */
    data object Closed : VaultViewerUiState

    /** The item is being authorized, opened and prepared. */
    data class Opening(override val item: VaultViewerItem) : VaultViewerUiState

    /** A decoded image is on screen. */
    data class Image(
        override val item: VaultViewerItem,
        val width: Int,
        val height: Int,
        /** Whether the decode was reduced to fit the viewer's bound, so the screen can say so. */
        val sampled: Boolean,
    ) : VaultViewerUiState

    /** A video is prepared, playing or paused. */
    data class Video(
        override val item: VaultViewerItem,
        val playing: Boolean,
        val positionMillis: Long,
        val durationMillis: Long,
    ) : VaultViewerUiState

    /** Audio is prepared, playing or paused. */
    data class Audio(
        override val item: VaultViewerItem,
        val playing: Boolean,
        val positionMillis: Long,
        val durationMillis: Long,
    ) : VaultViewerUiState

    /** A text document is on screen, bounded, and possibly longer than what is shown. */
    data class Text(
        override val item: VaultViewerItem,
        val characters: Int,
        val truncated: Boolean,
    ) : VaultViewerUiState

    /** A rendered document is on screen, one page at a time. */
    data class Document(
        override val item: VaultViewerItem,
        val page: Int,
        val pageCount: Int,
    ) : VaultViewerUiState

    /** This stage has no viewer for the item's type. The item is described, not shown. */
    data class Unsupported(override val item: VaultViewerItem) : VaultViewerUiState

    /** The index names this item and its encrypted object is not in the vault. */
    data class Missing(override val item: VaultViewerItem) : VaultViewerUiState

    /** The vault's storage could not be reached, or refused the read. */
    data class Unreadable(override val item: VaultViewerItem) : VaultViewerUiState

    /** The bytes authenticated as the wrong thing: the object is not shown at all. */
    data class Corrupt(override val item: VaultViewerItem) : VaultViewerUiState

    /** The content was read and the platform could not render or play it. */
    data class Failed(override val item: VaultViewerItem) : VaultViewerUiState

    /** The session ended while the viewer was open, or was not open when it was asked for. */
    data class Locked(override val item: VaultViewerItem?) : VaultViewerUiState

    /** The item the state is about, when it is about one. */
    val item: VaultViewerItem?
        get() = when (this) {
            Closed -> null
            is Opening -> item
            is Image -> item
            is Video -> item
            is Audio -> item
            is Text -> item
            is Document -> item
            is Unsupported -> item
            is Missing -> item
            is Unreadable -> item
            is Corrupt -> item
            is Failed -> item
            is Locked -> item
        }

    /** Whether the viewer is showing something for an item — that is, whether it holds content. */
    val isOpen: Boolean
        get() = this is Opening || this is Image || this is Video || this is Audio ||
            this is Text || this is Document
}

/**
 * Turns a read or decode failure into the state that describes it.
 *
 * This mapping is where "what went wrong" becomes "what a person is told", and it is exhaustive on
 * purpose: a new failure case must be given a sentence rather than falling into a generic one.
 */
internal fun VaultViewerFailure.toUiState(item: VaultViewerItem): VaultViewerUiState = when (this) {
    is VaultViewerFailure.Content -> when (failure) {
        VaultContentFailure.NotAuthorized -> VaultViewerUiState.Locked(item)
        VaultContentFailure.ContentMissing -> VaultViewerUiState.Missing(item)
        VaultContentFailure.Unreadable -> VaultViewerUiState.Unreadable(item)
        VaultContentFailure.Corrupt -> VaultViewerUiState.Corrupt(item)
        VaultContentFailure.KeyUnavailable -> VaultViewerUiState.Unreadable(item)
        is VaultContentFailure.VaultNotReady -> VaultViewerUiState.Unreadable(item)
    }

    VaultViewerFailure.DecodeFailed -> VaultViewerUiState.Failed(item)
    VaultViewerFailure.Unsupported -> VaultViewerUiState.Unsupported(item)
    // The viewer was closed while the item was opening: there is nothing to report to a screen that
    // is already gone.
    VaultViewerFailure.Cancelled -> VaultViewerUiState.Closed
}
