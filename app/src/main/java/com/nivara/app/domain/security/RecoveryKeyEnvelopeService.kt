package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult

/**
 * The cryptographic half of account recovery.
 *
 * A recovery key is a high-entropy random value, independent of the user's credential, that wraps
 * the same content key. Recovery therefore does not depend on remembering a PIN, and it cannot be
 * shortened to one: the recovery key is 256 random bits.
 *
 * This service generates recovery keys and seals content keys with them. It deliberately does
 * **not**:
 *
 * - persist anything. Writing the recovery key to a database, preferences, a file, a log or a
 *   screenshot is exactly the mistake this design exists to prevent, so no storage is offered
 *   here at all. When a later stage presents a recovery key to the user, it does so once, in
 *   memory, and stores only the sealed blob;
 * - handle the replacement-device flow, verified recovery attempts, attempt limits or the user
 *   interface. Those belong to the recovery stage;
 * - keep the sealed blob and the recovery key together. A recovery envelope is only as strong as
 *   the rule that the key that opens it is not stored next to it.
 *
 * The sealed blob is bound to [EncryptionContext.RecoveryEnvelope] and authenticated, so a blob
 * edited on disk, or sealed for another purpose, is rejected instead of producing a wrong key.
 */
interface RecoveryKeyEnvelopeService {

    /**
     * Generates a new 256-bit recovery key from the platform's secure random source.
     *
     * The returned value is the only copy: it is never stored by this service, never cached and
     * never logged. The caller is responsible for showing it to the user and for clearing it from
     * memory as soon as the recovery envelope exists.
     */
    fun generateRecoveryKey(): SensitiveBytes

    /** Seals [contentKey] under [recoveryKey]. The blob can be stored; the key cannot. */
    suspend fun sealContentKey(
        contentKey: EncryptionKey,
        recoveryKey: SensitiveBytes,
    ): NivaraResult<ByteArray>

    /**
     * Recovers the content key from a sealed envelope.
     *
     * Fails with [CryptographicFailure.AuthenticationFailed] when the envelope has been altered or
     * when [recoveryKey] is wrong, and with [CryptographicFailure.UnsupportedEnvelope] when the
     * blob is not a recovery envelope at all.
     */
    suspend fun unsealContentKey(
        envelope: ByteArray,
        recoveryKey: SensitiveBytes,
    ): NivaraResult<EncryptionKey>
}
