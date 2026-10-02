package com.nivara.app.testing

import com.nivara.app.data.credential.FileAttemptStore
import com.nivara.app.data.credential.FileCredentialRecordStore
import com.nivara.app.data.credential.NivaraCredentialManager
import com.nivara.app.data.credential.PersistedAttemptTracker
import com.nivara.app.data.security.Pbkdf2KeyDerivationService
import com.nivara.app.domain.credential.AttemptStore
import com.nivara.app.domain.credential.AttemptTracker
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialPolicy
import com.nivara.app.domain.credential.CredentialStore
import com.nivara.app.domain.credential.ExponentialThrottlePolicy
import com.nivara.app.domain.credential.ThrottlePolicy
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.KeyDerivationAlgorithm
import com.nivara.app.domain.security.KeyDerivationConfig
import com.nivara.app.domain.security.KeyDerivationService
import com.nivara.app.domain.security.SecureRandomGenerator
import java.io.File

/**
 * Test-only helpers for the credential layer.
 *
 * The cryptographic parts are the real ones — real PBKDF2-HMAC-SHA-256 from the platform
 * provider, real random salts, the real record format and the real file store. Only two things
 * are substituted, and neither of them is cryptography:
 *
 * - the iteration count is the lowest the policy permits, so the suite runs in seconds instead of
 *   minutes (a couple of tests use the production count to prove the default is the real one);
 * - the clock is a value the test controls, so delay windows can be tested without sleeping.
 *
 * Nothing here mocks the key derivation. A fake KDF would make authentication tests pass while
 * proving nothing about the code that actually runs on a device.
 */

/** Derivation cost used by these tests: the minimum the policy accepts, and still real PBKDF2. */
internal const val TEST_ITERATIONS: Int = 100_000

internal fun testKeyDerivationConfig(iterations: Int = TEST_ITERATIONS): KeyDerivationConfig = KeyDerivationConfig(
    algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
    saltBytes = KeyDerivationConfig.MINIMUM_SALT_BYTES,
    iterations = iterations,
    keySizeBits = KeyDerivationConfig.SUPPORTED_KEY_SIZE_BITS,
)

internal fun testKeyDerivationService(
    iterations: Int = TEST_ITERATIONS,
): KeyDerivationService = Pbkdf2KeyDerivationService(
    random = SecureRandomGenerator(),
    config = testKeyDerivationConfig(iterations),
)

internal fun testRecordStore(file: File): CredentialStore = FileCredentialRecordStore(file)

internal fun testAttemptStore(file: File): AttemptStore = FileAttemptStore(file)

internal fun testAttemptTracker(
    store: AttemptStore,
    timeProvider: TimeProvider,
    policy: ThrottlePolicy = ExponentialThrottlePolicy.Default,
): AttemptTracker = PersistedAttemptTracker(store = store, timeProvider = timeProvider, policy = policy)

internal fun testCredentialManager(
    keyDerivationService: KeyDerivationService,
    store: CredentialStore,
    attemptTracker: AttemptTracker,
    policy: CredentialPolicy = CredentialPolicy.Default,
): CredentialManager = NivaraCredentialManager(
    keyDerivationService = keyDerivationService,
    store = store,
    attemptTracker = attemptTracker,
    policy = policy,
)

/** A clock the test advances by hand, so throttling is verified without waiting. */
internal class MutableTimeProvider(private var currentMillis: Long = INITIAL_MILLIS) : TimeProvider {

    override fun nowMillis(): Long = currentMillis

    fun advanceBy(millis: Long) {
        currentMillis += millis
    }

    private companion object {
        const val INITIAL_MILLIS = 1_700_000_000_000L
    }
}

internal fun pin(value: String): CredentialInput.Pin = CredentialInput.Pin(value.toCharArray())

internal fun password(value: String): CredentialInput.Password = CredentialInput.Password(value.toCharArray())

internal fun pattern(vararg points: Int): CredentialInput.Pattern = CredentialInput.Pattern(intArrayOf(*points))

/**
 * `true` when [haystack] contains [needle] as a contiguous run of bytes.
 *
 * Used to assert that a persisted file does not contain a credential: if the search finds
 * nothing, the credential was not written in a form anything could read back.
 */
internal fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    for (start in 0..haystack.size - needle.size) {
        var matched = true
        for (offset in needle.indices) {
            if (haystack[start + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}
