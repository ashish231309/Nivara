package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult

/**
 * Recovering a vault after the app's own state is gone.
 *
 * A Nivara vault lives on the user's own storage, opened through a reference the platform grants.
 * Everything that lets the app open it day to day — the stored reference, the key that unwraps the
 * vault key — lives in the app's private storage, which does not survive a reinstall or a data
 * clear. The vault on the user's storage survives all of that; what this contract recovers is the
 * way back in.
 *
 * Recovery is deliberately narrow. It never creates a vault, never regenerates a key or an
 * identity, never rewrites content, and never repairs anything it finds damaged: it proves that a
 * folder holds a genuine vault, proves that the recovery material the user holds belongs to that
 * vault, unwraps the vault's existing key with it, and reconnects the app to exactly that vault.
 * Anything it cannot prove is a typed refusal.
 *
 * The mechanism it leans on is the recovery envelope the app has always had
 * (`RecoveryKeyEnvelopeService`): 256 random bits seal the vault key, and the same service opens
 * the seal. This contract adds where that envelope lives, how the key it yields is proven to
 * belong to the vault it was shown for, and how the reconnection is made durable — and nothing
 * else.
 */

/**
 * What recovery found at a location the user pointed at, before any secret is asked for.
 *
 * The survey reads only clear structure — markers and headers — because after a reinstall the app
 * holds no key that could open the vault's records. It is the step that separates "this is not a
 * vault" from "this is a vault recovery can try", without ever collapsing the two.
 */
sealed interface VaultRecoverySurvey {

    /**
     * The folder is not a Nivara vault: no metadata area, or none of Nivara's records inside it.
     *
     * Refused here so that a wrong pick — an ordinary folder, another app's files — can never be
     * adopted, initialized over, or asked for recovery material.
     */
    data object NotAVault : VaultRecoverySurvey

    /**
     * A genuine vault, but no recovery record was ever written into it.
     *
     * Vaults created before recovery existed, or created and never set up, are in this state. There
     * is nothing recovery can use and no substitute for it: the vault's key is wrapped only under
     * the app state that is gone. This is reported plainly rather than papered over.
     */
    data object RecoveryNotSetUp : VaultRecoverySurvey

    /**
     * A vault with a recovery record recovery can work with.
     *
     * The fingerprint is the vault's own identity as committed in its recovery record: a stable,
     * non-secret way for the user to confirm this is the vault they mean, before they spend their
     * recovery code on it. It is a fingerprint of the identity, never the identity's raw bytes and
     * never anything derived from the folder's name or path.
     */
    data class RecoveryAvailable(val identityFingerprint: String) : VaultRecoverySurvey

    /**
     * A vault whose recovery record is present but cannot be read: truncated or corrupted.
     *
     * Recovery refuses rather than guesses, and never repairs: the record is left exactly as found.
     */
    data object VaultDamaged : VaultRecoverySurvey

    /**
     * A vault whose recovery record was written by a version this build does not know.
     *
     * Like [VaultDamaged] this is a refusal, but a distinct one: the record may be perfectly sound,
     * just newer than this build, and the user is told so.
     */
    data object VaultUnsupported : VaultRecoverySurvey

    /**
     * The location exists but cannot be read: the grant is gone or the storage refuses access.
     *
     * Distinct from [NotAVault] on purpose — "I cannot look" is not "there is nothing there".
     */
    data object Unavailable : VaultRecoverySurvey
}

/**
 * Why a recovery attempt was refused.
 *
 * Each case is a distinct fact the user can act on; none of them is ever collapsed into another,
 * and none of them carries key material, references, or anything else the UI must not show. Like
 * every typed failure in Nivara, the messages are fixed, non-secret strings.
 */
sealed class VaultRecoveryFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The selected folder is not a Nivara vault. */
    data object NotAVault : VaultRecoveryFailure("the selected folder is not a Nivara vault")

    /** The vault was never given recovery material; there is nothing recovery can use. */
    data object RecoveryNotSetUp :
        VaultRecoveryFailure("this vault has no recovery material set up")

    /** The vault's records are present but do not authenticate; recovery refuses and repairs nothing. */
    data object VaultDamaged :
        VaultRecoveryFailure("the vault's records are damaged and recovery will not repair them")

    /** The recovery record was written by a version this build cannot read. */
    data object VaultUnsupported :
        VaultRecoveryFailure("the recovery record was written by a version Nivara cannot read")

    /** The location could not be read: the grant is gone or the storage refused access. */
    data object LocationUnavailable :
        VaultRecoveryFailure("the selected folder could not be read")

    /** The entered text is not a recovery code at all. */
    data object CodeMalformed :
        VaultRecoveryFailure("the entered text is not a recovery code")

    /** The entered text is a well-formed code whose checksum does not match. */
    data object CodeChecksumMismatch :
        VaultRecoveryFailure("the entered code does not match its own checksum")

    /**
     * The code decoded but does not open this vault's envelope.
     *
     * This covers the wrong code, a code from another vault, and a tampered envelope — the
     * envelope's authentication cannot tell them apart, and recovery does not try to.
     */
    data object WrongMaterial :
        VaultRecoveryFailure("the recovery code does not open this vault")

    /** Too many failed attempts; recovery is locked out until the stated time has passed. */
    data class Locked(val remainingMillis: Long) :
        VaultRecoveryFailure("recovery is locked; try again later")

    /**
     * The material opened the envelope, but the key it yielded is not committed to this vault's
     * identity, or the vault's records do not authenticate under it. Recovery stops before
     * reconnecting anything.
     */
    data object KeyMismatch :
        VaultRecoveryFailure("the recovered key does not belong to this vault")

    /** Reconnecting would have written, and the write or its verification failed; nothing moved. */
    data object WriteFailed :
        VaultRecoveryFailure("the reconnection could not be written and verified")

    /** The device could not provide the key reconnection wraps the vault key under. */
    data object KeyUnavailable :
        VaultRecoveryFailure("the device could not provide its wrapping key")

    /** Cryptographic machinery failed in a way none of the typed cases describes. */
    data object CryptographyFailed :
        VaultRecoveryFailure("a cryptographic step failed")

    /** The vault is not in a state recovery setup can write into. */
    data object VaultNotReady :
        VaultRecoveryFailure("the vault is not open for recovery setup")
}

/**
 * Whether the vault this installation is connected to has recovery material written into it.
 *
 * Drives the vault screen's setup prompt: a vault without a recovery record can still be used
 * normally, but it has no way back in once this installation's state is gone, and the screen says
 * so until setup happens.
 */
sealed interface RecoveryStatus {

    /** No vault is connected; there is nowhere a recovery record could live. */
    data object NoVault : RecoveryStatus

    /** A vault is connected but has no recovery record. */
    data object NotSetUp : RecoveryStatus

    /** A vault is connected and carries a recovery record. */
    data object SetUp : RecoveryStatus

    /** A vault is connected; its recovery record is present but unreadable. */
    data object Damaged : RecoveryStatus
}

/**
 * The recovery side of the vault: surveying a candidate location, reconnecting to it with recovery
 * material, and writing recovery material into the connected vault.
 *
 * All three are explicit, user-driven, and fail closed. None of them creates a vault, regenerates
 * an identity or a key, rewrites content, or repairs damage; the only change a successful recovery
 * makes is reconnecting this installation to the vault that was already there, and the only change
 * a successful setup makes is adding the recovery record beside the vault's existing records.
 */
interface VaultRecoveryRepository {

    /**
     * Surveys a candidate location for recovery, reading only clear structure.
     *
     * The location is one the user just selected; it is not adopted by this call, whatever the
     * survey finds.
     */
    suspend fun surveyRecovery(location: VaultLocation): NivaraResult<VaultRecoverySurvey>

    /**
     * Reconnects this installation to the vault at [location] using [code].
     *
     * The code is decoded, used to open the vault's recovery envelope, and the key it yields is
     * proven to belong to this vault before anything is reconnected. On success the vault's key is
     * re-wrapped under this installation's device key, the vault record is recommitted with the
     * vault's own identity, the location is adopted, and the vault opens exactly as it did before
     * its state was lost. On any failure nothing is adopted, written, or remembered.
     *
     * Returns the vault's identity so the caller can confirm what was reconnected.
     */
    suspend fun recover(location: VaultLocation, code: String): NivaraResult<VaultIdentity>

    /**
     * Writes recovery material into the connected vault, handing the one-time code to [presentCode].
     *
     * The block receives the code exactly once — the only moment it is ever shown — and its result
     * is the result of the call. The recovery key is generated fresh, used to seal the vault's
     * existing key, and cleared; it is never persisted, and neither is the code. Requires a vault
     * that is connected and openable in this installation.
     */
    suspend fun <T> setUpRecovery(
        presentCode: suspend (code: String) -> NivaraResult<T>,
    ): NivaraResult<T>

    /**
     * Reports whether the connected vault carries recovery material.
     *
     * Reads only the recovery record's clear header; it never opens the envelope and never needs
     * the vault's key.
     */
    suspend fun recoveryStatus(): NivaraResult<RecoveryStatus>
}

/**
 * The vault's identity as a fingerprint a person can read and compare.
 *
 * Grouped hexadecimal — four digits, a space, repeated. The identity is a non-secret vault label;
 * showing it grouped like this is how the user tells two vaults apart, without the app ever
 * showing raw bytes, keys, or anything derived from where the vault lives.
 */
fun VaultIdentity.displayFingerprint(): String {
    val hex = value
    val builder = StringBuilder(hex.length + hex.length / 4)
    hex.forEachIndexed { index, character ->
        if (index > 0 && index % 4 == 0) builder.append(' ')
        builder.append(character)
    }
    return builder.toString()
}
