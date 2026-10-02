package com.nivara.app.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.NivaraApplication
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.DeviceKeyStore
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Android Keystore implementation.
 *
 * These tests run on a device or emulator and exercise the real platform key store: nothing here
 * fakes a keystore, and no JVM test claims to have verified one. The JVM suite covers formats and
 * mathematics; only a device can show that key generation, non-exportability, invalidation and use
 * through `Cipher` behave as this layer assumes.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDeviceKeyStoreTest {

    private val deviceKeyStore: DeviceKeyStore = AndroidKeystoreDeviceKeyStore()
    private val random = SecureRandomGenerator()
    private val encryptionService: EncryptionService = JcaEncryptionService(random = random)

    /** Unique per test run, so a leftover key from an earlier run cannot influence a result. */
    private val alias = "nivara.test.${UUID.randomUUID()}"
    private val copiedAlias = "nivara.test.copy.${UUID.randomUUID()}"

    @Before
    fun setUp() {
        runBlocking { deleteTestKeys() }
    }

    @After
    fun tearDown() {
        runBlocking { deleteTestKeys() }
    }

    @Test
    fun keyDoesNotExistBeforeItIsCreated() {
        runBlocking {
            assertFalse(deviceKeyStore.exists(alias).valueOrNull()!!)
        }
    }

    @Test
    fun generatedKeyExistsIsUsableAndDeviceProtected() {
        runBlocking {
            val key = deviceKeyStore.getOrCreateKey(alias).valueOrNull()!!

            assertTrue(deviceKeyStore.exists(alias).valueOrNull()!!)
            assertTrue(deviceKeyStore.isKeyUsable(alias).valueOrNull()!!)
            assertTrue(key.isDeviceProtected)
            assertEquals(256, key.keySizeBits)
        }
    }

    @Test
    fun keyMaterialCannotBeExported() {
        runBlocking {
            deviceKeyStore.getOrCreateKey(alias).valueOrNull()

            // A keystore key has no encoded form: the material never enters the application.
            assertNull(AndroidKeystore.secretKeyFor(alias).encoded)
        }
    }

    @Test
    fun generatingTwiceKeepsTheExistingKey() {
        runBlocking {
            val first = deviceKeyStore.getOrCreateKey(alias).valueOrNull()!!
            val encryptedUnderFirst = encryptionService
                .encrypt("bound-to-first-key".toByteArray(), first, EncryptionContext.DeviceProtectedKey)
                .valueOrNull()!!

            val second = deviceKeyStore.getOrCreateKey(alias).valueOrNull()!!

            // Data encrypted under the original key still decrypts: the key was not replaced.
            assertEquals(
                "bound-to-first-key",
                encryptionService
                    .decrypt(encryptedUnderFirst, second, EncryptionContext.DeviceProtectedKey)
                    .valueOrNull()!!
                    .toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun retrievingAnUnknownAliasReportsKeyUnavailable() {
        runBlocking {
            assertEquals(CryptographicFailure.KeyUnavailable, deviceKeyStore.retrieveKey(alias).failureOrNull())
        }
    }

    @Test
    fun encryptionWithADeviceKeyRoundTrips() {
        runBlocking {
            val key = deviceKeyStore.getOrCreateKey(alias).valueOrNull()!!
            val payload = "application-security-state".toByteArray()

            val envelope = encryptionService
                .encrypt(payload, key, EncryptionContext.ApplicationSecurityData)
                .valueOrNull()!!
            val decrypted = encryptionService
                .decrypt(envelope, key, EncryptionContext.ApplicationSecurityData)
                .valueOrNull()!!

            assertArrayEquals(payload, decrypted)
        }
    }

    @Test
    fun aDeviceKeyRejectsAnEditedEnvelope() {
        runBlocking {
            val key = deviceKeyStore.getOrCreateKey(alias).valueOrNull()!!
            val envelope = encryptionService
                .encrypt("state".toByteArray(), key, EncryptionContext.ApplicationSecurityData)
                .valueOrNull()!!
            envelope[envelope.lastIndex] = (envelope[envelope.lastIndex].toInt() xor 0x01).toByte()

            assertEquals(
                CryptographicFailure.AuthenticationFailed,
                encryptionService.decrypt(envelope, key, EncryptionContext.ApplicationSecurityData).failureOrNull(),
            )
        }
    }

    @Test
    fun wrappingAContentKeyWithADeviceKeyRoundTrips() {
        runBlocking {
            val wrappingKey = deviceKeyStore.getOrCreateKey(alias).valueOrNull()!!
            val contentKey = EncryptionKey.fromRawBytes(random.nextKeyBytes(), label = "vault-key")
            val wrapper = NivaraContentKeyWrapper(random = random, encryptionService = encryptionService)

            val wrapped = wrapper.wrap(contentKey, wrappingKey).valueOrNull()!!
            val recovered = wrapper.unwrap(wrapped, wrappingKey).valueOrNull()!!

            assertEquals(256, recovered.keySizeBits)
            assertFalse(recovered.isDeviceProtected)

            // The recovered key must really work on data encrypted under the original key.
            val envelope = encryptionService
                .encrypt("vault-data".toByteArray(), contentKey, EncryptionContext.VaultContent)
                .valueOrNull()!!
            assertEquals(
                "vault-data",
                encryptionService
                    .decrypt(envelope, recovered, EncryptionContext.VaultContent)
                    .valueOrNull()!!
                    .toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun aCopiedAliasIsReportedAsUnusable() {
        runBlocking {
            deviceKeyStore.getOrCreateKey(alias).valueOrNull()

            // Copying the entry under another name leaves a handle whose platform key no longer
            // exists — the same situation as a key the platform has invalidated. It must be
            // reported as unusable instead of being used and failing later in a cipher.
            val keyStore = AndroidKeystore.load()
            keyStore.setKeyEntry(copiedAlias, keyStore.getKey(alias, null), null, null)

            assertFalse(deviceKeyStore.isKeyUsable(copiedAlias).valueOrNull()!!)
        }
    }

    @Test
    fun deletingAKeyRemovesIt() {
        runBlocking {
            deviceKeyStore.getOrCreateKey(alias).valueOrNull()

            deviceKeyStore.deleteKey(alias).valueOrNull()

            assertFalse(deviceKeyStore.exists(alias).valueOrNull()!!)
            assertFalse(deviceKeyStore.isKeyUsable(alias).valueOrNull()!!)
        }
    }

    @Test
    fun deletingAMissingKeySucceeds() {
        runBlocking {
            deviceKeyStore.deleteKey(alias).valueOrNull()

            assertFalse(deviceKeyStore.exists(alias).valueOrNull()!!)
        }
    }

    @Test
    fun theApplicationContainerProvidesWorkingCryptography() {
        runBlocking {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as NivaraApplication
            val container = application.container

            // Wiring check: the container hands out the real services and they work together.
            val salt = container.keyDerivationService.newSalt()
            assertEquals(16, salt.size)

            val derived = container.keyDerivationService.deriveKey("1234".toCharArray(), salt).valueOrNull()!!
            val contentKey = EncryptionKey.fromRawBytes(container.secureRandomGenerator.nextKeyBytes(), label = "vault-key")

            val wrapped = container.contentKeyWrapper.wrap(contentKey, derived).valueOrNull()!!
            val recovered = container.contentKeyWrapper.unwrap(wrapped, derived).valueOrNull()!!

            val envelope = container.encryptionService
                .encrypt("container-wiring".toByteArray(), recovered, EncryptionContext.VaultContent)
                .valueOrNull()!!
            assertEquals(
                "container-wiring",
                container.encryptionService
                    .decrypt(envelope, contentKey, EncryptionContext.VaultContent)
                    .valueOrNull()!!
                    .toString(Charsets.UTF_8),
            )

            val deviceKey = container.deviceKeyStore.getOrCreateKey(alias).valueOrNull()!!
            assertTrue(deviceKey.isDeviceProtected)
        }
    }

    private suspend fun deleteTestKeys() {
        deviceKeyStore.deleteKey(alias)
        deviceKeyStore.deleteKey(copiedAlias)
    }

    private fun NivaraResult<*>.failureOrNull(): Throwable? =
        (this as? NivaraResult.Failure)?.error
}
