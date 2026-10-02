package com.nivara.app.data.security

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.RecoveryKeyEnvelopeService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SensitiveBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [RecoveryKeyEnvelopeService] built on [SealedKeyContainer] with the recovery container family.
 *
 * Recovery keys are 256 bits from the platform's secure random source and are sealed with the same
 * HKDF-SHA-256 / XOR / HMAC-SHA-256 construction used for key wrapping, but under a different
 * magic (`NVRK`) and a different HKDF info prefix. A recovery envelope therefore cannot be opened
 * as a key-wrap blob, or the other way round, even if the same key were used for both.
 *
 * Nothing here stores anything. The service has no reference to preferences, files or a database,
 * which is the structural guarantee that a raw recovery key is never persisted beside the data it
 * protects: the caller receives it once, shows it to the user, seals the content key that needs
 * protecting, and clears it.
 */
internal class HkdfRecoveryKeyEnvelopeService(
    private val random: SecureRandomGenerator,
) : RecoveryKeyEnvelopeService {

    override fun generateRecoveryKey(): SensitiveBytes = random.nextKeyBytes()

    override suspend fun sealContentKey(
        contentKey: EncryptionKey,
        recoveryKey: SensitiveBytes,
    ): NivaraResult<ByteArray> = withContext(Dispatchers.Default) {
        nivaraRunCatching {
            requireUsableRecoveryKey(recoveryKey)
            val contentKeyMaterial = contentKey.exportKeyMaterial()
            try {
                val nonce = random.nextByteArray(SealedKeyContainer.NONCE_LENGTH)
                SealedKeyContainer.seal(
                    format = SealedKeyFormat.Recovery,
                    wrappingKey = recoveryKey.unsafeByteArray(),
                    contentKey = contentKeyMaterial,
                    nonce = nonce,
                )
            } finally {
                contentKeyMaterial.fill(0)
            }
        }
    }

    override suspend fun unsealContentKey(
        envelope: ByteArray,
        recoveryKey: SensitiveBytes,
    ): NivaraResult<EncryptionKey> = withContext(Dispatchers.Default) {
        nivaraRunCatching {
            requireUsableRecoveryKey(recoveryKey)
            val contentKeyMaterial = SealedKeyContainer.open(
                format = SealedKeyFormat.Recovery,
                wrappingKey = recoveryKey.unsafeByteArray(),
                container = envelope,
            )
            EncryptionKey.fromRawBytes(
                material = SensitiveBytes.wrap(contentKeyMaterial),
                label = RECOVERED_CONTENT_KEY_LABEL,
            )
        }
    }

    private fun requireUsableRecoveryKey(recoveryKey: SensitiveBytes) {
        if (recoveryKey.isCleared || recoveryKey.size != RECOVERY_KEY_BYTES) {
            throw CryptographicFailure.InvalidKey
        }
    }

    private companion object {
        const val RECOVERY_KEY_BYTES = 32
        const val RECOVERED_CONTENT_KEY_LABEL = "recovered-content-key"
    }
}
