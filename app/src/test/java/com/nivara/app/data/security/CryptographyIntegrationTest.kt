package com.nivara.app.data.security

import com.nivara.app.domain.security.ContentKeyWrapper
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.KeyDerivationAlgorithm
import com.nivara.app.domain.security.KeyDerivationConfig
import com.nivara.app.domain.security.KeyDerivationService
import com.nivara.app.domain.security.RecoveryKeyEnvelopeService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.testing.cryptographicFailure
import com.nivara.app.testing.material
import com.nivara.app.testing.valueOrFail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * End-to-end tests of the key hierarchy the vault stages will build on, exercised exactly the way
 * those stages intend to use it:
 *
 * ```
 * credential ──PBKDF2──► wrapping key ──┐
 *                                       ├── wraps ──► content key ──AES-256-GCM──► vault data
 * recovery key ─────────────────────────┘
 * ```
 *
 * The tests also cover what must *not* work: a different credential, a different recovery key and a
 * re-labelled envelope all fail instead of returning data.
 */
class CryptographyIntegrationTest {

    private val random = SecureRandomGenerator()
    private val encryptionService: EncryptionService = JcaEncryptionService(random = random)
    private val keyDerivationService: KeyDerivationService = Pbkdf2KeyDerivationService(random = random)
    private val contentKeyWrapper: ContentKeyWrapper = NivaraContentKeyWrapper(
        random = random,
        encryptionService = encryptionService,
    )
    private val recoveryService: RecoveryKeyEnvelopeService = HkdfRecoveryKeyEnvelopeService(random = random)

    private fun fastConfig(): KeyDerivationConfig = KeyDerivationConfig(
        algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
        saltBytes = 16,
        iterations = 100_000,
        keySizeBits = 256,
    )

    @Test
    fun `a content key protected by a credential can be recovered and used`() = runTest {
        val config = fastConfig()
        val salt = keyDerivationService.newSalt()
        val credential = "482916".toCharArray()

        // Enrolment: generate a random content key and protect it with a credential-derived key.
        val contentKey = EncryptionKey.fromRawBytes(random.nextKeyBytes(), label = "vault-key")
        val wrappingKey = keyDerivationService.deriveKey(credential, salt, config).valueOrFail()
        val wrappedContentKey = contentKeyWrapper.wrap(contentKey, wrappingKey).valueOrFail()

        val vaultRecord = "vault-index-v1".toByteArray()
        val storedEnvelope = encryptionService
            .encrypt(vaultRecord, contentKey, EncryptionContext.VaultMetadata)
            .valueOrFail()

        // Later unlock: derive the same wrapping key from the same credential and salt.
        val derivedAgain = keyDerivationService.deriveKey(credential, salt, config).valueOrFail()
        val recoveredContentKey = contentKeyWrapper.unwrap(wrappedContentKey, derivedAgain).valueOrFail()

        assertArrayEquals(contentKey.material(), recoveredContentKey.material())
        assertArrayEquals(
            vaultRecord,
            encryptionService.decrypt(storedEnvelope, recoveredContentKey, EncryptionContext.VaultMetadata).valueOrFail(),
        )
    }

    @Test
    fun `a wrong credential cannot recover the content key or read the data`() = runTest {
        val config = fastConfig()
        val salt = keyDerivationService.newSalt()

        val contentKey = EncryptionKey.fromRawBytes(random.nextKeyBytes(), label = "vault-key")
        val enrolmentWrappingKey = keyDerivationService.deriveKey("482916".toCharArray(), salt, config).valueOrFail()
        val wrappedContentKey = contentKeyWrapper.wrap(contentKey, enrolmentWrappingKey).valueOrFail()
        val storedEnvelope = encryptionService
            .encrypt("vault-index-v1".toByteArray(), contentKey, EncryptionContext.VaultMetadata)
            .valueOrFail()

        // A wrong credential derives a different wrapping key: unwrapping must fail, and no key
        // may be handed back that would allow a decryption attempt.
        val wrongWrappingKey = keyDerivationService.deriveKey("482917".toCharArray(), salt, config).valueOrFail()

        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            contentKeyWrapper.unwrap(wrappedContentKey, wrongWrappingKey).cryptographicFailure(),
        )
        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            encryptionService.decrypt(storedEnvelope, wrongWrappingKey, EncryptionContext.VaultMetadata)
                .cryptographicFailure(),
        )
    }

    @Test
    fun `a credential change only requires re-wrapping the content key`() = runTest {
        val config = fastConfig()
        val salt = keyDerivationService.newSalt()

        val contentKey = EncryptionKey.fromRawBytes(random.nextKeyBytes(), label = "vault-key")
        val oldWrappingKey = keyDerivationService.deriveKey("111111".toCharArray(), salt, config).valueOrFail()
        val wrappedWithOldCredential = contentKeyWrapper.wrap(contentKey, oldWrappingKey).valueOrFail()

        val data = "encrypted-payload".toByteArray()
        val storedEnvelope = encryptionService.encrypt(data, contentKey, EncryptionContext.VaultContent).valueOrFail()

        // New credential: unwrap with the old one, wrap again with the new one. The data (and the
        // content key) never change, so nothing has to be re-encrypted.
        val recovered = contentKeyWrapper.unwrap(wrappedWithOldCredential, oldWrappingKey).valueOrFail()
        val newSalt = keyDerivationService.newSalt()
        val newWrappingKey = keyDerivationService.deriveKey("222222".toCharArray(), newSalt, config).valueOrFail()
        val wrappedWithNewCredential = contentKeyWrapper.wrap(recovered, newWrappingKey).valueOrFail()

        assertNotEquals(wrappedWithOldCredential.toList(), wrappedWithNewCredential.toList())

        val recoveredAgain = contentKeyWrapper.unwrap(wrappedWithNewCredential, newWrappingKey).valueOrFail()
        assertArrayEquals(
            data,
            encryptionService.decrypt(storedEnvelope, recoveredAgain, EncryptionContext.VaultContent).valueOrFail(),
        )
    }

    @Test
    fun `a recovery key can recover the same content key without the credential`() = runTest {
        val config = fastConfig()
        val salt = keyDerivationService.newSalt()

        val contentKey = EncryptionKey.fromRawBytes(random.nextKeyBytes(), label = "vault-key")
        val wrappingKey = keyDerivationService.deriveKey("482916".toCharArray(), salt, config).valueOrFail()
        val wrappedByCredential = contentKeyWrapper.wrap(contentKey, wrappingKey).valueOrFail()

        // Enrolment also seals the content key with a recovery key, shown once to the user.
        val recoveryKey = recoveryService.generateRecoveryKey()
        val recoveryEnvelope = recoveryService.sealContentKey(contentKey, recoveryKey).valueOrFail()

        val storedEnvelope = encryptionService
            .encrypt("vault-index-v1".toByteArray(), contentKey, EncryptionContext.VaultMetadata)
            .valueOrFail()

        // Recovery: neither the credential nor its salt is needed.
        val recoveredContentKey = recoveryService.unsealContentKey(recoveryEnvelope, recoveryKey).valueOrFail()

        assertArrayEquals(contentKey.material(), recoveredContentKey.material())
        assertEquals(
            "vault-index-v1",
            encryptionService
                .decrypt(storedEnvelope, recoveredContentKey, EncryptionContext.VaultMetadata)
                .valueOrFail()
                .toString(Charsets.UTF_8),
        )
        // Both protections of the same content key coexist.
        assertArrayEquals(
            contentKey.material(),
            contentKeyWrapper.unwrap(wrappedByCredential, wrappingKey).valueOrFail().material(),
        )
    }

    @Test
    fun `a recovery envelope cannot be replayed as vault metadata`() = runTest {
        val contentKey = EncryptionKey.fromRawBytes(random.nextKeyBytes(), label = "vault-key")
        val recoveryKey = recoveryService.generateRecoveryKey()
        val recoveryEnvelope = recoveryService.sealContentKey(contentKey, recoveryKey).valueOrFail()

        // A recovery container is not an envelope at all, so it is rejected before any crypto.
        assertEquals(
            CryptographicFailure.UnsupportedEnvelope,
            encryptionService
                .decrypt(recoveryEnvelope, contentKey, EncryptionContext.RecoveryEnvelope)
                .cryptographicFailure(),
        )
    }

    @Test
    fun `vault data encrypted for one purpose cannot be read as another purpose`() = runTest {
        val contentKey = EncryptionKey.fromRawBytes(random.nextKeyBytes(), label = "vault-key")
        val vaultData = encryptionService
            .encrypt("file-bytes".toByteArray(), contentKey, EncryptionContext.VaultContent)
            .valueOrFail()

        assertEquals(
            CryptographicFailure.ContextMismatch,
            encryptionService.decrypt(vaultData, contentKey, EncryptionContext.RecoveryEnvelope).cryptographicFailure(),
        )
    }
}
