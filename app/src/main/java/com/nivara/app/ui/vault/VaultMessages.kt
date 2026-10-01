package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultState
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
 * Deliberately coarse: what a file *is* for storage purposes is its declared type, and Nivara shows
 * the broad family so a long list can be scanned. Reading the file's contents, extracting a thumbnail
 * or opening a viewer belongs to the stage that owns presentation.
 */
internal fun vaultItemTypeRes(mimeType: String?): Int {
    val type = mimeType?.substringBefore('/')?.lowercase()
    return when (type) {
        "image" -> R.string.vault_item_type_image
        "video" -> R.string.vault_item_type_video
        "audio" -> R.string.vault_item_type_audio
        "text" -> R.string.vault_item_type_text
        "application" -> R.string.vault_item_type_document
        else -> R.string.vault_item_type_other
    }
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

/** Shown when the user asked to import while the gate was closed. */
internal fun vaultImportLockedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_import_locked)

/** Shown when a picked document could not be turned into a reference Nivara can use. */
internal fun vaultImportSelectionFailedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_import_error_selection_failed)

/** Shown when an import failed for a reason Nivara cannot name. */
internal fun vaultImportFailedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.vault_import_error_generic)
