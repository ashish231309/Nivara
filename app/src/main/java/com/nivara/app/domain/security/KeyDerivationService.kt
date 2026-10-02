package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult

/**
 * Derives encryption keys from a user credential.
 *
 * The credential is a `CharArray` rather than a `String` on purpose: `String` is immutable, so a
 * derived credential would stay in the heap until the collector happens to reclaim it and could
 * not be cleared at all. Callers keep the credential in a `CharArray`, pass it here, and clear it
 * immediately afterwards.
 *
 * [deriveKey] never decides *whether* a credential is correct. Verification is a comparison
 * performed by the authentication stage against stored verification data; this service only
 * turns a credential into a key, and it holds no credential state.
 *
 * Implementations must perform the derivation off the main thread: the configured iteration count
 * takes hundreds of milliseconds by design.
 */
interface KeyDerivationService {

    /** Parameters used when none are supplied. Persisted with the data they protect. */
    val config: KeyDerivationConfig

    /**
     * Derives a 256-bit key from [password] using [salt] and [config].
     *
     * The same credential, salt and parameters always produce the same key; a different salt or a
     * different iteration count produces a different key. That property is what makes the
     * parameters storable alongside the ciphertext.
     *
     * The caller owns [password] and must clear it after this call returns. The returned key
     * carries key material and must be cleared once a session ends.
     */
    suspend fun deriveKey(
        password: CharArray,
        salt: SensitiveBytes,
        config: KeyDerivationConfig = this.config,
    ): NivaraResult<EncryptionKey>

    /** A new random salt sized according to [config]. One salt per credential, never reused. */
    fun newSalt(): SensitiveBytes
}
