package com.nivara.app.data.security

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.KeyDerivationAlgorithm
import com.nivara.app.domain.security.KeyDerivationConfig
import com.nivara.app.domain.security.KeyDerivationService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SensitiveBytes
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Credential-based key derivation with PBKDF2-HMAC-SHA-256.
 *
 * The credential is passed as a `CharArray` and handed straight to `PBEKeySpec`, which copies it
 * into its own buffer; that buffer is cleared in a `finally` block. The credential is never
 * converted to a `String` — a `String` is immutable, cannot be cleared, and would leave the
 * credential in the heap for an unknown amount of time.
 *
 * PBKDF2 is deliberately the only algorithm enabled for now: it is part of the platform provider,
 * so it works identically on API 28 through current Android, and Nivara does not have to ship a
 * native library to support old devices. The iteration count is a recorded parameter, so it can be
 * raised for new data without making existing data unreadable.
 *
 * Derivation is CPU-bound by design (600 000 iterations by default) and always runs on
 * [Dispatchers.Default].
 */
internal class Pbkdf2KeyDerivationService(
    private val random: SecureRandomGenerator,
    override val config: KeyDerivationConfig = KeyDerivationConfig.Pbkdf2HmacSha256Default,
) : KeyDerivationService {

    override fun newSalt(): SensitiveBytes = random.nextBytes(config.saltBytes)

    override suspend fun deriveKey(
        password: CharArray,
        salt: SensitiveBytes,
        config: KeyDerivationConfig,
    ): NivaraResult<EncryptionKey> = withContext(Dispatchers.Default) {
        nivaraRunCatching {
            validate(password = password, salt = salt, config = config)

            val keySpec = PBEKeySpec(
                password,
                salt.unsafeByteArray(),
                config.iterations,
                config.keySizeBits,
            )
            try {
                val factory = SecretKeyFactory.getInstance(jcaName(config.algorithm))
                val encoded = factory.generateSecret(keySpec).encoded
                    ?: throw CryptographicFailure.InvalidParameters
                // Ownership of `encoded` moves into the key; it is cleared when the key is cleared.
                EncryptionKey.fromRawBytes(
                    material = SensitiveBytes.wrap(encoded),
                    label = "derived:${config.algorithm.name}:v${config.version}",
                )
            } finally {
                keySpec.clearPassword()
            }
        }
    }

    private fun validate(password: CharArray, salt: SensitiveBytes, config: KeyDerivationConfig) {
        if (password.isEmpty()) {
            throw CryptographicFailure.InvalidParameters
        }
        if (salt.isCleared || salt.size < KeyDerivationConfig.MINIMUM_SALT_BYTES) {
            throw CryptographicFailure.InvalidParameters
        }
        if (config.keySizeBits != KeyDerivationConfig.SUPPORTED_KEY_SIZE_BITS) {
            throw CryptographicFailure.InvalidParameters
        }
        if (config.iterations < config.algorithm.minimumIterations) {
            // Refuse a downgraded work factor rather than deriving a weaker key from stored
            // parameters that were tampered with.
            throw CryptographicFailure.InvalidParameters
        }
    }

    private fun jcaName(algorithm: KeyDerivationAlgorithm): String = when (algorithm) {
        KeyDerivationAlgorithm.Pbkdf2HmacSha256 -> "PBKDF2WithHmacSHA256"
    }
}
