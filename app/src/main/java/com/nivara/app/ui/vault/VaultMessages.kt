package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultContentClassification
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultOrganizationFailure
import com.nivara.app.domain.vault.VaultOrganizationUnreadable
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultTrashFailure
import com.nivara.app.domain.vault.VaultTrashUnreadable
import com.nivara.app.domain.vault.VaultUnreadableReason
import com.nivara.app.ui.components.NivaraMessage

/**
 * Copy for the vault screen.
 *
 * Every message is a fixed, non-secret sentence: none of them names a folder, a platform URI, a file
 * name, a key or a platform exception, and none of them can be read as "the vault is empty". Failures
 * are turned into text here, in the layer that owns user-facing wording, so no failure object reaches
 * a composable.
 */
internal fun VaultFailure.asMessage(): NivaraMessage = NivaraMessage(textRes = messageRes())

private fun VaultFailure.messageRes(): Int = when (this) {
    VaultFailure.InvalidLocation -> R.string.vault_error_invalid_location
    VaultFailure.LocationUnreadable -> R.string.vault_error_location_unreadable
    VaultFailure.AccessDenied -> R.string.vault_error_access_denied
    VaultFailure.StorageUnavailable -> R.string.vault_error_storage_unavailable
    VaultFailure.VaultAlreadyExists -> R.string.vault_error_already_exists
    is VaultFailure.VaultUnreadable -> R.string.vault_error_not_replaced
    is VaultFailure.UnsupportedVersion -> R.string.vault_error_not_replaced
    VaultFailure.WriteFailed -> R.string.vault_error_write_failed
    VaultFailure.VerificationFailed -> R.string.vault_error_not_verified
    VaultFailure.KeyUnavailable -> R.string.vault_error_key_unavailable
    VaultFailure.CryptographyFailed -> R.string.vault_error_cryptography
}

/**
 * The heading for a state.
 *
 * Every state has its own wording, and the two cases that look similar from outside are kept apart:
 * a record that cannot be read is a vault that exists and cannot be opened, while missing structure is
 * usually a setup that did not finish — and they need different sentences, because the reader has to
 * decide whether to try again or to stop.
 */
internal fun VaultState.titleRes(): Int = when (this) {
    VaultState.NotConfigured -> R.string.vault_state_not_configured_title
    VaultState.LocationUnknown -> R.string.vault_state_location_unknown_title
    VaultState.Missing -> R.string.vault_state_missing_title
    is VaultState.Ready -> R.string.vault_state_ready_title
    is VaultState.Unreadable -> when (reason) {
        VaultUnreadableReason.MetadataDamaged -> R.string.vault_state_unreadable_title
        VaultUnreadableReason.StructureIncomplete -> R.string.vault_state_incomplete_title
        VaultUnreadableReason.KeyUnavailable -> R.string.vault_state_unreadable_title
    }
    is VaultState.UnsupportedVersion -> R.string.vault_state_unsupported_title
    VaultState.Unavailable -> R.string.vault_state_unavailable_title
    VaultState.AccessDenied -> R.string.vault_state_access_denied_title
}

/** What the state means, in the user's own terms. */
internal fun VaultState.bodyRes(): Int = when (this) {
    VaultState.NotConfigured -> R.string.vault_state_not_configured
    VaultState.LocationUnknown -> R.string.vault_state_location_unknown
    VaultState.Missing -> R.string.vault_state_missing
    is VaultState.Ready -> R.string.vault_state_ready
    is VaultState.Unreadable -> when (reason) {
        VaultUnreadableReason.MetadataDamaged -> R.string.vault_state_unreadable_metadata
        VaultUnreadableReason.StructureIncomplete -> R.string.vault_state_unreadable_structure
        VaultUnreadableReason.KeyUnavailable -> R.string.vault_state_unreadable_key
    }
    is VaultState.UnsupportedVersion -> R.string.vault_state_unsupported
    VaultState.Unavailable -> R.string.vault_state_unavailable
    VaultState.AccessDenied -> R.string.vault_state_access_denied
}

/** Shown when the user asked for a change while the gate was closed. */
internal fun vaultLockedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_locked)

/** Shown when a folder selection could not be adopted. */
internal fun vaultSelectionFailedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_error_selection_failed)

/**
 * Copy for the list of files in the vault.
 *
 * Each state says what it is and, where it matters, what it is not: a list that cannot be read is not
 * an empty list, an interrupted import is not a file, and an encrypted object nobody listed has not
 * been deleted. The item list itself is drawn by the screen; these are the words around it.
 */
internal fun VaultIndexUiState.titleRes(): Int = when (this) {
    VaultIndexUiState.Loading -> R.string.vault_index_title
    VaultIndexUiState.VaultNotReady -> R.string.vault_index_title
    VaultIndexUiState.Empty -> R.string.vault_index_empty_title
    is VaultIndexUiState.Indexed -> R.string.vault_index_items_title
    is VaultIndexUiState.Unreadable -> when (reason) {
        VaultIndexUnreadable.MetadataDamaged -> R.string.vault_index_unreadable_title
        VaultIndexUnreadable.KeyUnavailable -> R.string.vault_index_unreadable_key_title
    }
    VaultIndexUiState.UnsupportedVersion -> R.string.vault_index_unsupported_title
    VaultIndexUiState.Unavailable -> R.string.vault_index_unavailable_title
    VaultIndexUiState.AccessDenied -> R.string.vault_index_access_denied_title
}

/** What the list's state means, or `null` when the list itself is the body. */
internal fun VaultIndexUiState.bodyRes(): Int? = when (this) {
    VaultIndexUiState.Loading -> R.string.vault_index_loading
    VaultIndexUiState.VaultNotReady -> R.string.vault_index_not_ready
    VaultIndexUiState.Empty -> R.string.vault_index_empty
    is VaultIndexUiState.Indexed -> null
    is VaultIndexUiState.Unreadable -> when (reason) {
        VaultIndexUnreadable.MetadataDamaged -> R.string.vault_index_unreadable
        VaultIndexUnreadable.KeyUnavailable -> R.string.vault_index_unreadable_key
    }
    VaultIndexUiState.UnsupportedVersion -> R.string.vault_index_unsupported
    VaultIndexUiState.Unavailable -> R.string.vault_index_unavailable
    VaultIndexUiState.AccessDenied -> R.string.vault_index_access_denied
}

/** What has to be said about a readable list besides the files themselves. */
internal fun VaultIndexUiState.noticesRes(): List<VaultIndexNotice> {
    if (this !is VaultIndexUiState.Indexed) return emptyList()
    val notices = mutableListOf<VaultIndexNotice>()
    if (missingContent > 0) {
        notices += VaultIndexNotice(R.string.vault_index_missing_content, missingContent)
    }
    if (unfinishedObjects != null && unfinishedObjects > 0) {
        notices += VaultIndexNotice(R.string.vault_index_unfinished_objects, unfinishedObjects)
    }
    if (unindexedObjects != null && unindexedObjects > 0) {
        notices += VaultIndexNotice(R.string.vault_index_unindexed_objects, unindexedObjects)
    }
    return notices
}

/**
 * The generic kind of an imported file.
 *
 * The kind comes from the vault's one classifier — the Android-free
 * [VaultContentClassification] — rather than from a second set of prefix rules written for the screen.
 * Two classifiers would eventually disagree, and a list that called a file a document while its
 * viewer called it something else is a contradiction the user would see.
 */
internal fun vaultItemTypeRes(item: VaultItemUi): Int = when (item.kind) {
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
 * Why an import did not happen.
 *
 * Every case says what Nivara did *not* do, because the only thing that must never be said about a
 * failed import is that the file is in the vault.
 */
internal fun VaultImportFailure.asMessage(): NivaraMessage = NivaraMessage(textRes = messageRes())

private fun VaultImportFailure.messageRes(): Int = when (this) {
    VaultImportFailure.NotAuthorized -> R.string.vault_import_error_not_authorized
    is VaultImportFailure.VaultNotReady -> R.string.vault_import_error_vault_not_ready
    is VaultImportFailure.IndexUnreadable -> R.string.vault_import_error_index_unreadable
    is VaultImportFailure.UnsupportedIndexVersion -> R.string.vault_import_error_index_unsupported
    VaultImportFailure.IndexFull -> R.string.vault_import_error_index_full
    VaultImportFailure.SourceUnavailable -> R.string.vault_import_error_source_unavailable
    VaultImportFailure.SourceAccessDenied -> R.string.vault_import_error_source_access_denied
    VaultImportFailure.SourceTooLarge -> R.string.vault_import_error_source_too_large
    VaultImportFailure.InvalidSourceName -> R.string.vault_import_error_source_name
    VaultImportFailure.StorageUnavailable -> R.string.vault_import_error_storage_unavailable
    VaultImportFailure.AccessDenied -> R.string.vault_import_error_access_denied
    VaultImportFailure.WriteFailed -> R.string.vault_import_error_write_failed
    VaultImportFailure.VerificationFailed -> R.string.vault_import_error_not_verified
    VaultImportFailure.KeyUnavailable -> R.string.vault_import_error_key_unavailable
    VaultImportFailure.CryptographyFailed -> R.string.vault_import_error_cryptography
    VaultImportFailure.DuplicateItemId -> R.string.vault_import_error_duplicate_id
}

/**
 * Text for the album record's own states.
 *
 * The headings and bodies sit beside the index's for the same reason it has them: an album record that
 * cannot be read must be explained as *that*, and never allowed to read as "you have no albums".
 */
internal fun VaultOrganizationUiState.titleRes(): Int? = when (this) {
    VaultOrganizationUiState.Loading,
    VaultOrganizationUiState.VaultNotReady,
    VaultOrganizationUiState.Empty,
    is VaultOrganizationUiState.Albums,
    -> null

    is VaultOrganizationUiState.Unreadable -> R.string.vault_albums_unreadable_title
    VaultOrganizationUiState.UnsupportedVersion -> R.string.vault_albums_unsupported_title
    VaultOrganizationUiState.Unavailable -> R.string.vault_albums_unavailable_title
    VaultOrganizationUiState.AccessDenied -> R.string.vault_albums_access_denied_title
}

/** The sentence under an album-record state that needs one. */
internal fun VaultOrganizationUiState.bodyRes(): Int? = when (this) {
    is VaultOrganizationUiState.Unreadable -> when (reason) {
        VaultOrganizationUnreadable.MetadataDamaged -> R.string.vault_albums_unreadable_body
        VaultOrganizationUnreadable.KeyUnavailable -> R.string.vault_albums_key_unavailable_body
    }

    VaultOrganizationUiState.UnsupportedVersion -> R.string.vault_albums_unsupported_body
    VaultOrganizationUiState.Unavailable -> R.string.vault_albums_unavailable_body
    VaultOrganizationUiState.AccessDenied -> R.string.vault_albums_access_denied_body
    else -> null
}

/** Every album change that failed says why, and none of them says "the albums are gone". */
internal fun VaultOrganizationFailure.asMessage(): NivaraMessage = NivaraMessage(textRes = messageRes())

private fun VaultOrganizationFailure.messageRes(): Int = when (this) {
    VaultOrganizationFailure.NotAuthorized -> R.string.vault_album_error_not_authorized
    is VaultOrganizationFailure.VaultNotReady -> R.string.vault_album_error_vault_not_ready
    // A record that cannot be opened because the key is missing says the same thing as a change that
    // failed for that reason: the cause is one thing, and two sentences for it would be two ideas.
    is VaultOrganizationFailure.OrganizationUnreadable -> when (reason) {
        VaultOrganizationUnreadable.MetadataDamaged -> R.string.vault_album_error_unreadable
        VaultOrganizationUnreadable.KeyUnavailable -> R.string.vault_album_error_key_unavailable
    }
    is VaultOrganizationFailure.UnsupportedVersion -> R.string.vault_album_error_unsupported
    VaultOrganizationFailure.MetadataUnavailable -> R.string.vault_album_error_unavailable
    VaultOrganizationFailure.AccessDenied -> R.string.vault_album_error_access_denied
    VaultOrganizationFailure.AlbumNotFound -> R.string.vault_album_error_not_found
    VaultOrganizationFailure.InvalidAlbumName -> R.string.vault_album_error_invalid_name
    VaultOrganizationFailure.AlbumNameUnchanged -> R.string.vault_album_error_name_unchanged
    VaultOrganizationFailure.AlbumFull -> R.string.vault_album_error_album_full
    VaultOrganizationFailure.OrganizationFull -> R.string.vault_album_error_too_many
    VaultOrganizationFailure.StorageUnavailable -> R.string.vault_album_error_storage_unavailable
    VaultOrganizationFailure.WriteFailed -> R.string.vault_album_error_write_failed
    VaultOrganizationFailure.VerificationFailed -> R.string.vault_album_error_not_verified
    VaultOrganizationFailure.KeyUnavailable -> R.string.vault_album_error_key_unavailable
    VaultOrganizationFailure.CryptographyFailed -> R.string.vault_album_error_cryptography
}

/** Shown when an album was asked for while the album record is not in a state that may be changed. */
internal fun vaultOrganizationUnavailableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_album_error_unavailable)

/** Shown when an album change failed for a reason Nivara cannot name. */
internal fun vaultAlbumChangeFailedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_album_error_failed)

/**
 * Text for the trash record's own states.
 *
 * The headings and bodies sit beside the index's and the albums' for the same reason they have them: a
 * record that cannot be read must be explained as *that*, and never allowed to read as "your trash is
 * empty".
 */
internal fun VaultTrashUiState.titleRes(): Int? = when (this) {
    VaultTrashUiState.Loading,
    VaultTrashUiState.VaultNotReady,
    VaultTrashUiState.Empty,
    is VaultTrashUiState.Trashed,
    -> null

    is VaultTrashUiState.Unreadable -> R.string.vault_trash_unreadable_title
    VaultTrashUiState.UnsupportedVersion -> R.string.vault_trash_unsupported_title
    VaultTrashUiState.Unavailable -> R.string.vault_trash_unavailable_title
    VaultTrashUiState.AccessDenied -> R.string.vault_trash_access_denied_title
}

/** The sentence under a trash-record state that needs one. */
internal fun VaultTrashUiState.bodyRes(): Int? = when (this) {
    is VaultTrashUiState.Unreadable -> when (reason) {
        VaultTrashUnreadable.MetadataDamaged -> R.string.vault_trash_unreadable_body
        VaultTrashUnreadable.KeyUnavailable -> R.string.vault_trash_key_unavailable_body
    }

    VaultTrashUiState.UnsupportedVersion -> R.string.vault_trash_unsupported_body
    VaultTrashUiState.Unavailable -> R.string.vault_trash_unavailable_body
    VaultTrashUiState.AccessDenied -> R.string.vault_trash_access_denied_body
    else -> null
}

/** Every trash change that failed says why, and none of them says a file was deleted. */
internal fun VaultTrashFailure.asMessage(): NivaraMessage = NivaraMessage(textRes = messageRes())

private fun VaultTrashFailure.messageRes(): Int = when (this) {
    VaultTrashFailure.NotAuthorized -> R.string.vault_trash_error_not_authorized
    is VaultTrashFailure.VaultNotReady -> R.string.vault_trash_error_vault_not_ready
    is VaultTrashFailure.TrashUnreadable -> when (reason) {
        VaultTrashUnreadable.MetadataDamaged -> R.string.vault_trash_error_unreadable
        VaultTrashUnreadable.KeyUnavailable -> R.string.vault_trash_error_key_unavailable
    }
    is VaultTrashFailure.UnsupportedVersion -> R.string.vault_trash_error_unsupported
    VaultTrashFailure.MetadataUnavailable -> R.string.vault_trash_error_unavailable
    VaultTrashFailure.AccessDenied -> R.string.vault_trash_error_access_denied
    is VaultTrashFailure.IndexUnreadable -> R.string.vault_trash_error_index_unreadable
    is VaultTrashFailure.IndexUnsupportedVersion -> R.string.vault_trash_error_index_unsupported
    VaultTrashFailure.IndexUnavailable -> R.string.vault_trash_error_index_unavailable
    VaultTrashFailure.ItemNotInVault -> R.string.vault_trash_error_item_not_in_vault
    VaultTrashFailure.TrashFull -> R.string.vault_trash_error_full
    VaultTrashFailure.StorageUnavailable -> R.string.vault_trash_error_storage_unavailable
    VaultTrashFailure.WriteFailed -> R.string.vault_trash_error_write_failed
    VaultTrashFailure.VerificationFailed -> R.string.vault_trash_error_not_verified
    VaultTrashFailure.KeyUnavailable -> R.string.vault_trash_error_key_unavailable
    VaultTrashFailure.CryptographyFailed -> R.string.vault_trash_error_cryptography
}

/** Shown when the trash was asked for while its record is not in a state that may be changed. */
internal fun vaultTrashUnavailableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_trash_error_not_writable)

/** Shown when a trash change failed for a reason Nivara cannot name. */
internal fun vaultTrashChangeFailedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_trash_error_failed)

/** Shown when the user asked to import while the gate was closed. */
internal fun vaultImportLockedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_import_locked)

/** Shown when a picked document could not be turned into a reference Nivara can use. */
internal fun vaultImportSelectionFailedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_import_error_selection_failed)

/** Shown when an import failed for a reason Nivara cannot name. */
internal fun vaultImportFailedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_import_error_generic)
