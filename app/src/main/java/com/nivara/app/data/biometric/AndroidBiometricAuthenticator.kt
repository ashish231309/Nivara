package com.nivara.app.data.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.security.AndroidBiometricKeyStore
import com.nivara.app.domain.credential.AttemptTracker
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.ThrottleState
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricPromptHost
import com.nivara.app.domain.security.BiometricState
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.BiometricThrottlePolicy
import com.nivara.app.domain.security.BiometricUnavailability
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.SecureRandomGenerator
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/**
 * [BiometricAuthenticator] built on Android's own prompt and Android's own key store.
 *
 * ### What this class decides
 *
 * Only three things: *when* to ask the platform to authenticate, what to do with the answer, and
 * how long to wait after its own failures. Matching, template storage, the prompt, the sensor
 * state and the hardware lockout are the platform's, and nothing here can influence them.
 *
 * ### What it stores
 *
 * A short record holding a random token encrypted by a Keystore key that requires a strong
 * biometric for every single use, plus the IV of that one operation. The key itself never leaves
 * the platform and cannot be exported. No fingerprint, face or template data is ever received by
 * this application, let alone written anywhere: Android owns the templates and the matching, and
 * the only thing that crosses the boundary is yes or no.
 *
 * ### How it relates to the primary credential
 *
 * Nothing here turns on without a configured primary credential, nothing here can replace one, and
 * no failure here is ever recorded against the credential's attempt counter — the two counters
 * live in different stores and mean different things. When the platform invalidates the biometric
 * key (the usual cause is a change in the device's biometric enrolment), the configuration is
 * reported as [BiometricStatus.Invalidated], the primary credential is untouched, and the user may
 * turn biometric unlock on again deliberately. It is never recreated silently.
 *
 * ### What it cannot do
 *
 * It cannot lift Android's lockout, and it does not try. Biometric failures are throttled by
 * Nivara's own rule ([BiometricThrottlePolicy]) *beside* the platform's lockout; when the platform
 * reports a lockout, that is what the user is told, and the primary credential remains the way in.
 */
internal class AndroidBiometricAuthenticator(
    private val context: Context,
    private val credentialManager: CredentialManager,
    private val keyStore: AndroidBiometricKeyStore,
    private val tokenStore: BiometricTokenStore,
    private val attempts: AttemptTracker,
    private val random: SecureRandomGenerator,
    private val timeProvider: TimeProvider,
    private val policy: BiometricThrottlePolicy = BiometricThrottlePolicy.Default,
) : BiometricAuthenticator {

    /**
     * The screen Android's prompt is attached to.
     *
     * Held weakly and released by the screen itself, so an activity that is being destroyed cannot
     * be kept alive by the authenticator, and a recreated screen cannot lose its own registration.
     */
    private var host: WeakReference<FragmentActivity>? = null

    override fun attachHost(host: BiometricPromptHost) {
        require(host is FragmentActivity) {
            "the platform biometric prompt must be hosted by the application's activity"
        }
        this.host = WeakReference(host)
    }

    override fun detachHost(host: BiometricPromptHost) {
        // Identity, not equality: a screen that is shutting down releases itself and must not
        // release the screen that replaced it.
        if (this.host?.get() === host) {
            this.host = null
        }
    }

    override suspend fun state(): BiometricState = BiometricState(
        status = status(),
        retryAfterMillis = attempts.remainingBlockMillis(),
    )

    override suspend fun enable(): NivaraResult<Unit> {
        val credentialStatus = credentialManager.status().valueOrNull()
            ?: return NivaraResult.Failure(BiometricFailure.StorageUnavailable)
        if (credentialStatus !is CredentialStatus.Configured) {
            // Biometric unlock is a convenience for an existing credential, never a way to create
            // an account state of its own.
            return NivaraResult.Failure(BiometricFailure.PrimaryCredentialRequired)
        }

        availabilityReason()?.let { reason ->
            return NivaraResult.Failure(BiometricFailure.Unavailable(reason))
        }

        // Turning on something that is already on changes nothing and is not an error.
        if (status() == BiometricStatus.Enabled) return NivaraResult.Success(Unit)

        val activity = host?.get()
            ?: return NivaraResult.Failure(BiometricFailure.Unavailable(BiometricUnavailability.Unknown))

        // Start from a clean slate: a leftover key or record from an interrupted setup must not
        // survive into the new configuration.
        removeConfiguration()

        val created = keyStore.create(KEY_ALIAS)
        if (created is NivaraResult.Failure) {
            return NivaraResult.Failure(created.asBiometricKeyFailure())
        }

        val cipher = keyStore.encryptionCipher(KEY_ALIAS).valueOrNull()
        if (cipher == null) {
            removeConfiguration()
            return NivaraResult.Failure(BiometricFailure.KeyStoreUnavailable)
        }

        return when (val result = BiometricPromptRunner(activity).authenticate(cipher, enableCopy())) {
            is PromptResult.Succeeded -> storeToken(result.cipher ?: cipher, result.failedAttempts)
            is PromptResult.Error -> {
                recordFailures(result.failedAttempts)
                removeConfiguration()
                NivaraResult.Failure(promptErrorFailure(result.errorCode))
            }
        }
    }

    override suspend fun authenticate(): BiometricAuthenticationOutcome {
        attempts.remainingBlockMillis().takeIf { it > 0L }?.let { remaining ->
            // Nivara's own delay. It is checked before anything else, so a throttled path cannot
            // reach the sensor at all — the platform's lockout, when it exists, is reported by the
            // prompt itself and is never something this check can shorten.
            return BiometricAuthenticationOutcome.TemporarilyBlocked(remaining)
        }

        availabilityReason()?.let { reason ->
            return BiometricAuthenticationOutcome.Unavailable(reason)
        }

        val token = tokenStore.load() ?: return BiometricAuthenticationOutcome.NotEnabled
        val activity = host?.get()
            ?: return BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.Unknown)

        // A key that cannot even be prepared for the prompt is a configuration that cannot
        // authenticate anything, so the user is told to set it up again. Nothing is recreated here.
        val cipher = keyStore.decryptionCipher(KEY_ALIAS, token.iv).valueOrNull()
            ?: return BiometricAuthenticationOutcome.Invalidated

        return when (val result = BiometricPromptRunner(activity).authenticate(cipher, authenticateCopy())) {
            is PromptResult.Succeeded -> unlocked(result, token, cipher)
            is PromptResult.Error -> {
                val state = recordFailures(result.failedAttempts)
                when {
                    // The platform's lockout is reported as itself, whoever caused it.
                    promptErrorOutcome(result.errorCode) is BiometricAuthenticationOutcome.SystemBlocked ->
                        promptErrorOutcome(result.errorCode)

                    // Someone presented a biometric and it did not match: that is the useful fact,
                    // even when the prompt ended with a cancellation afterwards.
                    result.failedAttempts > 0 -> failed(state)

                    else -> promptErrorOutcome(result.errorCode)
                }
            }
        }
    }

    override suspend fun disable(): NivaraResult<Unit> {
        val token = tokenStore.load() ?: return NivaraResult.Failure(BiometricFailure.NotEnabled)
        val keyUsable = keyStore.isUsable(KEY_ALIAS).valueOrNull() == true

        if (!requiresPromptBeforeRemoval(keyUsable = keyUsable, availability = availabilityReason())) {
            // A configuration whose key can no longer authenticate anything, on hardware that
            // cannot authenticate at all, takes no capability away when it is removed.
            removeConfiguration()
            return NivaraResult.Success(Unit)
        }

        val activity = host?.get()
            ?: return NivaraResult.Failure(BiometricFailure.Unavailable(BiometricUnavailability.Unknown))

        val cipher = keyStore.decryptionCipher(KEY_ALIAS, token.iv).valueOrNull()
        if (cipher == null) {
            removeConfiguration()
            return NivaraResult.Success(Unit)
        }

        return when (val result = BiometricPromptRunner(activity).authenticate(cipher, disableCopy())) {
            is PromptResult.Succeeded -> {
                removeConfiguration()
                NivaraResult.Success(Unit)
            }
            is PromptResult.Error -> {
                recordFailures(result.failedAttempts)
                NivaraResult.Failure(promptErrorFailure(result.errorCode))
            }
        }
    }

    override suspend fun clearFailures() {
        attempts.recordSuccess()
    }

    /**
     * Decrypts the stored token, which is only possible after the platform accepted the user.
     *
     * The decrypted bytes are cleared immediately and nothing is derived from them: they exist to
     * prove that the key worked, not to protect anything. A tag failure means the stored record and
     * the key no longer belong together, which the user resolves by turning biometric unlock on
     * again.
     */
    private suspend fun unlocked(
        result: PromptResult.Succeeded,
        token: BiometricToken,
        cipher: Cipher,
    ): BiometricAuthenticationOutcome {
        val unlockedCipher = result.cipher ?: cipher
        val tokenBytes = try {
            unlockedCipher.doFinal(token.ciphertext)
        } catch (refused: GeneralSecurityException) {
            null
        } catch (refused: IllegalStateException) {
            null
        }

        if (tokenBytes == null) {
            recordFailures(result.failedAttempts)
            return BiometricAuthenticationOutcome.Invalidated
        }

        tokenBytes.fill(ZERO_BYTE)
        attempts.recordSuccess()
        return BiometricAuthenticationOutcome.Succeeded
    }

    /** Encrypts a fresh random token under the newly authorized key and stores the record. */
    private suspend fun storeToken(cipher: Cipher, failedAttempts: Int): NivaraResult<Unit> {
        recordFailures(failedAttempts)

        val token = random.nextBytes(SecureRandomGenerator.KEY_SIZE_BYTES)
        val stored = try {
            BiometricToken(
                iv = cipher.iv ?: throw IllegalStateException("the platform produced no IV"),
                ciphertext = cipher.doFinal(token.unsafeByteArray()),
            )
        } catch (refused: GeneralSecurityException) {
            null
        } catch (refused: IllegalStateException) {
            null
        } finally {
            // The ciphertext is already a copy; the plaintext token must not outlive its use.
            token.clear()
        }

        if (stored == null) {
            removeConfiguration()
            return NivaraResult.Failure(BiometricFailure.KeyStoreUnavailable)
        }

        val saved = tokenStore.save(stored)
        if (saved is NivaraResult.Failure) {
            removeConfiguration()
            return saved
        }

        attempts.recordSuccess()
        return NivaraResult.Success(Unit)
    }

    /** Removes every trace of biometric unlock. The primary credential is not touched. */
    private suspend fun removeConfiguration() {
        tokenStore.delete()
        keyStore.delete(KEY_ALIAS)
        attempts.recordSuccess()
    }

    /** Records [count] rejected attempts and returns the state they produce. */
    private suspend fun recordFailures(count: Int): ThrottleState {
        var state = attempts.currentState()
        repeat(count) { state = attempts.recordFailure() }
        return state
    }

    /** Describes a failed attempt, including whatever delay it triggered. */
    private fun failed(state: ThrottleState): BiometricAuthenticationOutcome.Failed =
        BiometricAuthenticationOutcome.Failed(
            attemptsRemaining = (policy.freeAttempts - state.consecutiveFailures).coerceAtLeast(0),
            blockedForMillis = state.remainingBlockMillis(timeProvider.nowMillis()),
        )

    /** The configuration's state, from the record it stores and the key that protects it. */
    private suspend fun status(): BiometricStatus {
        val configured = tokenStore.load() != null
        val keyUsable = configured && keyStore.isUsable(KEY_ALIAS).valueOrNull() == true
        return BiometricStatus.resolve(
            configured = configured,
            keyUsable = keyUsable,
            unavailability = availabilityReason(),
        )
    }

    /** The platform's own capability report, or `null` when the device can authenticate now. */
    private fun availabilityReason(): BiometricUnavailability? = try {
        biometricAvailability(
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG),
        )
    } catch (unreported: Exception) {
        // A platform that cannot answer the question is reported as not answering, never as
        // available: the primary credential remains the way in.
        BiometricUnavailability.Unknown
    }

    private fun enableCopy(): PromptCopy = promptCopy(
        titleRes = R.string.biometric_prompt_enable_title,
        subtitleRes = R.string.biometric_prompt_enable_subtitle,
    )

    private fun authenticateCopy(): PromptCopy = promptCopy(
        titleRes = R.string.biometric_prompt_authenticate_title,
        subtitleRes = R.string.biometric_prompt_authenticate_subtitle,
    )

    private fun disableCopy(): PromptCopy = promptCopy(
        titleRes = R.string.biometric_prompt_disable_title,
        subtitleRes = R.string.biometric_prompt_disable_subtitle,
    )

    private fun promptCopy(titleRes: Int, subtitleRes: Int): PromptCopy = PromptCopy(
        title = context.getString(titleRes),
        subtitle = context.getString(subtitleRes),
        negativeButton = context.getString(R.string.biometric_prompt_negative),
    )

    private companion object {
        /** Versioned so a future format change cannot be mistaken for this one. */
        const val KEY_ALIAS = "nivara.biometric.v1"

        const val ZERO_BYTE: Byte = 0
    }
}

/**
 * Whether removing the stored configuration needs a successful biometric authentication first.
 *
 * A configuration whose key the platform has already invalidated is dead weight: removing it takes
 * no capability away, and no prompt could authenticate it. The same is true for hardware that
 * cannot authenticate at all — no sensor, no enrolment, no supported configuration, or a device
 * that needs a security update first.
 *
 * In every other case, including a sensor that is merely busy or a state the platform did not
 * report, the user must authenticate before the biometric path is removed.
 */
internal fun requiresPromptBeforeRemoval(
    keyUsable: Boolean,
    availability: BiometricUnavailability?,
): Boolean {
    if (!keyUsable) return false
    return when (availability) {
        null,
        BiometricUnavailability.HardwareUnavailable,
        BiometricUnavailability.Unknown,
        -> true

        BiometricUnavailability.NoHardware,
        BiometricUnavailability.NotEnrolled,
        BiometricUnavailability.Unsupported,
        BiometricUnavailability.SecurityUpdateRequired,
        -> false
    }
}

/** Narrows a key-store failure to the biometric failure a caller can act on. */
private fun NivaraResult<*>.asBiometricKeyFailure(): BiometricFailure =
    when ((this as? NivaraResult.Failure)?.error) {
        is CryptographicFailure.KeyGenerationFailed -> BiometricFailure.KeyGenerationFailed
        else -> BiometricFailure.KeyStoreUnavailable
    }
