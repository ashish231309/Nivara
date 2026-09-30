package com.nivara.app.data.security

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [EncryptionService] implemented on the JCA provider that Android ships.
 *
 * AES-256-GCM with a fresh random 96-bit nonce per encryption and a 128-bit tag. The provider is
 * the platform's own (`Conscrypt` on Android, hardware-backed for keystore keys where the device
 * supports it), so no third-party crypto library and no native code enter the application.
 *
 * Work runs on [Dispatchers.Default]: a device-protected key makes `Cipher.init` call into the
 * platform key store, which must never happen on the main thread.
 */
internal class JcaEncryptionService(
    private val random: SecureRandomGenerator,
) : EncryptionService {

    override suspend fun encrypt(
        plaintext: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
        clearPlaintextAfterUse: Boolean,
    ): NivaraResult<ByteArray> = withContext(Dispatchers.Default) {
        nivaraRunCatching {
            val nonce = random.nextByteArray(EncryptedEnvelope.NONCE_LENGTH)
            val envelope = EncryptedEnvelope.encrypt(plaintext, key.asSecretKey(), context, nonce)
            // Only clear the caller's buffer once the ciphertext exists: if encryption failed,
            // the caller still has their data and can decide what to do about the failure.
            if (clearPlaintextAfterUse) {
                plaintext.fill(0)
            }
            envelope
        }
    }

    override suspend fun decrypt(
        envelope: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<ByteArray> = withContext(Dispatchers.Default) {
        nivaraRunCatching {
            EncryptedEnvelope.decrypt(envelope, key.asSecretKey(), context)
        }
    }
}
