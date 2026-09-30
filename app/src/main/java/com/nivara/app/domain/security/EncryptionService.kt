package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult

/**
 * Authenticated encryption for small, in-memory payloads.
 *
 * Every call produces or consumes a versioned envelope (see `docs/crypto/envelope-format.md`):
 * AES-256-GCM under a 256-bit key, a fresh random nonce per encryption, a 128-bit authentication
 * tag, and the declared [EncryptionContext] bound into the authenticated data.
 *
 * Callers never build a `Cipher`, choose a mode or supply a nonce. That is deliberate: the
 * limited ways of misusing an AEAD (fixed nonce, ECB, missing tag check, unauthenticated
 * metadata) are all outside this API.
 *
 * Scope: metadata, keys and other small values. Large payloads — future vault files — will be
 * split into chunks by a streaming service built on this interface, so nothing here buffers more
 * than it was given.
 *
 * Implementations must run off the main thread when a device-protected key is involved, because
 * that path calls into the platform key store.
 */
interface EncryptionService {

    /**
     * Encrypts [plaintext] into a new envelope bound to [context].
     *
     * A fresh random nonce is generated for every call, so encrypting the same plaintext with the
     * same key twice produces different ciphertext and never reuses a nonce.
     *
     * @param clearPlaintextAfterUse overwrites the caller's [plaintext] buffer once the ciphertext
     *   exists. Use it for values that will not be needed again; it never touches the returned
     *   envelope. As with all erasure on a managed runtime, this reduces exposure without
     *   guaranteeing it (see [SensitiveBytes]).
     */
    suspend fun encrypt(
        plaintext: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
        clearPlaintextAfterUse: Boolean = false,
    ): NivaraResult<ByteArray>

    /**
     * Decrypts an envelope produced by [encrypt].
     *
     * Fails with [CryptographicFailure.AuthenticationFailed] when the envelope, its header, its
     * nonce, the associated data or the key do not agree — before returning any plaintext. Fails
     * with a typed envelope failure for unsupported versions, schemes, algorithms and purposes,
     * and with [CryptographicFailure.ContextMismatch] when [context] differs from the purpose the
     * envelope was created for. There is no fallback path and no partial output.
     */
    suspend fun decrypt(
        envelope: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<ByteArray>
}
