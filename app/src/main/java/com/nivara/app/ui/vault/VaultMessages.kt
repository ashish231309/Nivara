package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.domain.vault.VaultFailure
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
