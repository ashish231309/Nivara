package com.nivara.app.ui.vault.viewer

import com.nivara.app.R
import com.nivara.app.domain.vault.VaultContentClassification
import com.nivara.app.domain.vault.VaultContentKind
import java.util.Locale

/**
 * What the viewer says about an item's type.
 *
 * The kind comes from the one classifier the vault has — the Android-free
 * [com.nivara.app.domain.vault.VaultContentClassification] — rather than from a second set of rules
 * written for the screen: a file the list calls a document and the viewer calls something else would
 * be a contradiction the user could see.
 */
internal fun vaultViewerTypeRes(item: VaultViewerItem): Int = when (item.kind) {
    VaultContentKind.Image -> R.string.vault_item_type_image
    VaultContentKind.Video -> R.string.vault_item_type_video
    VaultContentKind.Audio -> R.string.vault_item_type_audio
    VaultContentKind.Document ->
        if (VaultContentClassification.isReadableText(item.mimeType)) {
            R.string.vault_item_type_text
        } else {
            R.string.vault_item_type_document
        }

    VaultContentKind.Other -> R.string.vault_item_type_other
}

/**
 * The title and body of a state that has something to explain.
 *
 * `null` for the states that show the item itself: an image is its own explanation, and a body
 * sentence over it would only be in the way.
 */
internal fun VaultViewerUiState.titleRes(): Int? = when (this) {
    VaultViewerUiState.Closed -> null
    is VaultViewerUiState.Opening -> R.string.vault_viewer_opening
    is VaultViewerUiState.Image -> null
    is VaultViewerUiState.Video -> null
    is VaultViewerUiState.Audio -> null
    is VaultViewerUiState.Text -> null
    is VaultViewerUiState.Document -> null
    is VaultViewerUiState.Unsupported -> R.string.vault_viewer_unsupported_title
    is VaultViewerUiState.Missing -> R.string.vault_viewer_missing_title
    is VaultViewerUiState.Unreadable -> R.string.vault_viewer_unreadable_title
    is VaultViewerUiState.Corrupt -> R.string.vault_viewer_corrupt_title
    is VaultViewerUiState.Failed -> R.string.vault_viewer_failed_title
    is VaultViewerUiState.Locked -> R.string.vault_viewer_locked_title
}

/** The sentence under a title, or `null` when the state needs none. */
internal fun VaultViewerUiState.bodyRes(): Int? = when (this) {
    is VaultViewerUiState.Unsupported -> R.string.vault_viewer_unsupported_body
    is VaultViewerUiState.Missing -> R.string.vault_viewer_missing_body
    is VaultViewerUiState.Unreadable -> R.string.vault_viewer_unreadable_body
    is VaultViewerUiState.Corrupt -> R.string.vault_viewer_corrupt_body
    is VaultViewerUiState.Failed -> R.string.vault_viewer_failed_body
    is VaultViewerUiState.Locked -> R.string.vault_viewer_locked_body
    else -> null
}

/**
 * Whether the state offers to try again.
 *
 * Only where trying again can differ: storage that was out of reach, a decoder that may have been
 * busy. A missing object, a type with no viewer and content that did not authenticate are facts, and
 * offering to repeat them would suggest Nivara could change them.
 */
internal fun VaultViewerUiState.canRetry(): Boolean = this is VaultViewerUiState.Unreadable ||
    this is VaultViewerUiState.Failed

/** Whether the state would be fixed by unlocking — the one state the credential screen answers. */
internal fun VaultViewerUiState.needsUnlock(): Boolean = this is VaultViewerUiState.Locked

/**
 * A duration or position as `m:ss`, or `h:mm:ss` past an hour.
 *
 * Formatted here rather than by a platform helper so the viewer's own formatting is testable on the
 * JVM, and so no locale-dependent formatting turns a position into something unexpected.
 */
internal fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1_000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
