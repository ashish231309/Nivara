package com.nivara.app.data.credential

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isSuccess
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.credential.StoredCredential
import com.nivara.app.domain.credential.ThrottleState
import com.nivara.app.domain.security.KeyDerivationAlgorithm
import com.nivara.app.domain.security.KeyDerivationConfig
import com.nivara.app.testing.testAttemptStore
import com.nivara.app.testing.testRecordStore
import com.nivara.app.testing.valueOrFail
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the two files the credential layer writes.
 *
 * These exercise the production store: the same class, the same format and the same atomic write
 * that run on a device. Only the directory differs — a temporary folder instead of the
 * application's private storage — which is exactly why the stores take a `File` rather than a
 * `Context`.
 */
class CredentialPersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recordFile: File get() = File(temporaryFolder.root, "credential.nvc")
    private val attemptFile: File get() = File(temporaryFolder.root, "credential-attempts.nva")

    private val credential = StoredCredential(
        type = PrimaryCredentialType.Password,
        config = KeyDerivationConfig(
            algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
            saltBytes = KeyDerivationConfig.MINIMUM_SALT_BYTES,
            iterations = 600_000,
            keySizeBits = KeyDerivationConfig.SUPPORTED_KEY_SIZE_BITS,
        ),
        salt = ByteArray(KeyDerivationConfig.MINIMUM_SALT_BYTES) { index -> (index * 7).toByte() },
        verifier = ByteArray(CredentialRecordCodec.VERIFIER_BYTES) { index -> (index + 11).toByte() },
    )

    // ------------------------------------------------------------------ credential record

    @Test
    fun `nothing is configured before anything is written`() = runTest {
        val loaded = testRecordStore(recordFile).load().valueOrFail()

        assertNull(loaded)
        assertFalse(recordFile.exists())
    }

    @Test
    fun `a stored record survives a new store instance`() = runTest {
        assertTrue(testRecordStore(recordFile).save(credential).isSuccess)

        // A fresh store, as a restarted process would create.
        val reloaded = testRecordStore(recordFile).load().valueOrFail()

        requireNotNull(reloaded)
        assertEquals(credential.type, reloaded.type)
        assertEquals(credential.config, reloaded.config)
        assertArrayEquals(credential.salt, reloaded.salt)
        assertArrayEquals(credential.verifier, reloaded.verifier)
    }

    @Test
    fun `saving replaces the previous record and leaves no temporary file behind`() = runTest {
        val store = testRecordStore(recordFile)
        store.save(credential)

        val replacement = StoredCredential(
            type = PrimaryCredentialType.Pin,
            config = credential.config,
            salt = ByteArray(KeyDerivationConfig.MINIMUM_SALT_BYTES) { 9.toByte() },
            verifier = ByteArray(CredentialRecordCodec.VERIFIER_BYTES) { 3.toByte() },
        )
        assertTrue(store.save(replacement).isSuccess)

        val reloaded = store.load().valueOrFail()
        requireNotNull(reloaded)
        assertEquals(PrimaryCredentialType.Pin, reloaded.type)
        assertArrayEquals(replacement.salt, reloaded.salt)

        val leftovers = temporaryFolder.root.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("a temporary file was left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `a damaged record is reported instead of being repaired`() = runTest {
        recordFile.writeBytes("this is not a credential record".toByteArray())

        val result = testRecordStore(recordFile).load()

        assertTrue(result is NivaraResult.Failure)
        assertEquals(CredentialFailure.InvalidConfiguration, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `a record that is truncated is reported as unusable`() = runTest {
        val store = testRecordStore(recordFile)
        store.save(credential)
        val complete = recordFile.readBytes()
        recordFile.writeBytes(complete.copyOf(complete.size - 4))

        val result = store.load()

        assertTrue(result is NivaraResult.Failure)
        assertEquals(CredentialFailure.InvalidConfiguration, (result as NivaraResult.Failure).error)
    }

    // ------------------------------------------------------------------ attempt counters

    @Test
    fun `nothing is recorded before the first failure`() = runTest {
        assertEquals(ThrottleState.Clear, testAttemptStore(attemptFile).load().valueOrFail())
    }

    @Test
    fun `attempt counters survive a new store instance`() = runTest {
        val state = ThrottleState(consecutiveFailures = 4, blockedUntilMillis = 1_700_000_030_000L)

        assertTrue(testAttemptStore(attemptFile).save(state).isSuccess)
        assertEquals(state, testAttemptStore(attemptFile).load().valueOrFail())
    }

    @Test
    fun `a damaged counter file reads as nothing recorded`() = runTest {
        attemptFile.writeBytes(ByteArray(64) { 1.toByte() })

        // Not a failure: a rate limiter must never become a lock the user cannot open.
        assertEquals(ThrottleState.Clear, testAttemptStore(attemptFile).load().valueOrFail())
    }

    @Test
    fun `a counter file of the wrong version reads as nothing recorded`() = runTest {
        val state = ThrottleState(consecutiveFailures = 2, blockedUntilMillis = 1L)
        val encoded = ThrottleStateCodec.encode(state)
        encoded[4] = 42
        attemptFile.writeBytes(encoded)

        assertEquals(ThrottleState.Clear, testAttemptStore(attemptFile).load().valueOrFail())
    }
}
