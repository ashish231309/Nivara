package com.nivara.app.ui.vault.recovery

import com.nivara.app.ui.components.NivaraMessage

/**
 * What the recovery screen draws.
 *
 * One state type rather than many: the screen is a straight line — choose a folder, look at it,
 * enter the code, done — and every phase is the same few facts arranged differently. The phases
 * never collapse into each other: "not a vault" is not "no vault here", "recovery was not set up"
 * is not "damaged", and none of them is ever drawn as an empty vault.
 */
data class VaultRecoveryUiState(
    val phase: VaultRecoveryPhase = VaultRecoveryPhase.SelectLocation,
    val busy: Boolean = false,
    val codeInput: String = "",
    val failure: NivaraMessage? = null,
)

/**
 * The phases a recovery attempt moves through.
 *
 * They correspond one-to-one to the connection states recovery exists to keep apart: selecting a
 * location, what the survey found at it, the code itself being required, the attempt running, and
 * the reconnected vault at the end. A failed attempt returns to the phase it started from with a
 * typed failure message; it never falls back to an earlier phase as if nothing was found.
 */
sealed interface VaultRecoveryPhase {

    /** Nothing selected yet: the screen offers the folder picker and explains itself. */
    data object SelectLocation : VaultRecoveryPhase

    /** The selected folder is not a Nivara vault. */
    data object NotAVault : VaultRecoveryPhase

    /** A genuine vault, but no recovery code was ever set up for it. */
    data object RecoveryNotSetUp : VaultRecoveryPhase

    /** A vault whose recovery record is present but cannot be read. */
    data object VaultDamaged : VaultRecoveryPhase

    /** A vault whose recovery record was written by a newer Nivara. */
    data object VaultUnsupported : VaultRecoveryPhase

    /** The selected folder could not be read: grant gone or storage refused. */
    data object LocationUnavailable : VaultRecoveryPhase

    /**
     * A vault recovery can work with. The fingerprint identifies it so the user can confirm which
     * vault they selected before spending the code on it.
     */
    data class RecoveryRequired(val identityFingerprint: String) : VaultRecoveryPhase

    /** Reconnection succeeded; the screen reports it and offers the way back to the vault. */
    data class Reconnected(val identityFingerprint: String) : VaultRecoveryPhase
}
