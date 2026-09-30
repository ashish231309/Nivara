package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult

/**
 * Protects one key with another key.
 *
 * Nivara follows the standard key hierarchy: a single high-entropy content key (the vault key)
 * encrypts the actual data, and that content key is stored wrapped by whatever protects the
 * account — a credential-derived key, a device key that never leaves the platform key store, or a
 * recovery key. Consequences of that shape:
 *
 * - re-wrapping the content key (changing a credential, enrolling an additional wrapping key)
 *   never touches the encrypted data, because the data keys do not change;
 * - a credential never encrypts file data directly, so a weak credential is only ever attacked
 *   through one PBKDF2-protected wrapping, not once per file;
 * - losing one wrapping key does not necessarily lose the data, as long as another wrapping of
 *   the same content key survives — which is what makes both recovery and credential changes
 *   possible.
 *
 * A wrapped key is an opaque byte blob. Callers store it; they cannot read the content key from
 * it, and Nivara never writes a content key in the clear.
 */
interface ContentKeyWrapper {

    /**
     * Wraps [contentKey] under [wrappingKey].
     *
     * The wrapping scheme is chosen from the kind of [wrappingKey] (device-protected keys are used
     * through the platform key store and cannot be exported), and that choice is recorded in the
     * blob, so [unwrap] never has to be told which scheme was used.
     */
    suspend fun wrap(
        contentKey: EncryptionKey,
        wrappingKey: EncryptionKey,
        context: EncryptionContext = EncryptionContext.KeyWrapping,
    ): NivaraResult<ByteArray>

    /**
     * Recovers the content key from a blob produced by [wrap].
     *
     * Fails with [CryptographicFailure.AuthenticationFailed] if the blob, its header or its
     * nonce has been altered, or if [wrappingKey] is not the key the blob was wrapped with:
     * a wrong key never yields "a key that might work", it yields a refusal.
     */
    suspend fun unwrap(
        wrappedKey: ByteArray,
        wrappingKey: EncryptionKey,
        context: EncryptionContext = EncryptionContext.KeyWrapping,
    ): NivaraResult<EncryptionKey>
}
