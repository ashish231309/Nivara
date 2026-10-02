package com.nivara.app.data.credential

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.fold
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.AttemptTracker
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialPolicy
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.CredentialStore
import com.nivara.app.domain.credential.PatternCanonicalizer
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.credential.StoredCredential
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.KeyDerivationService
import com.nivara.app.domain.security.SensitiveBytes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The credential layer's implementation.
 *
 * It adds no cryptography of its own: the key comes from the Stage 2
 * [KeyDerivationService], the salt from the same service's random source, and the only new
 * construction is the one-way verifier in [CredentialVerifier], which is a keyed hash of an
 * existing key rather than a new primitive.
 *
 * Public operations are serialised by a mutex and run off the main thread. Serialising matters
 * for correctness rather than performance: two concurrent enrollments must not both observe "no
 * credential configured" and both write a record, and an attempt counter must not lose an
 * increment because two verifications interleaved.
 */
internal class NivaraCredentialManager(
    private val keyDerivationService: KeyDerivationService,
    private val store: CredentialStore,
    private val attemptTracker: AttemptTracker,
    private val policy: CredentialPolicy = CredentialPolicy.Default,
) : CredentialManager {

    private val mutex = Mutex()

    override suspend fun status(): NivaraResult<CredentialStatus> = withContext(Dispatchers.IO) {
        mutex.withLock { loadStatus() }
    }

    override suspend fun enroll(
        credential: CredentialInput,
        confirmation: CredentialInput,
    ): NivaraResult<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock { enrollLocked(credential, confirmation) }
            }
        } finally {
            credential.clear()
            confirmation.clear()
        }
    }

    override suspend fun verify(credential: CredentialInput): AuthenticationOutcome {
        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock { verifyLocked(credential) }
            }
        } finally {
            credential.clear()
        }
    }

    override suspend fun change(
        current: CredentialInput,
        credential: CredentialInput,
        confirmation: CredentialInput,
    ): NivaraResult<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock { changeLocked(current, credential, confirmation) }
            }
        } finally {
            current.clear()
            credential.clear()
            confirmation.clear()
        }
    }

    // ------------------------------------------------------------------ enrollment and change

    private suspend fun enrollLocked(
        credential: CredentialInput,
        confirmation: CredentialInput,
    ): NivaraResult<Unit> {
        val existing = store.load()
        if (existing is NivaraResult.Failure) return NivaraResult.Failure(existing.error)
        if (existing.valueOrNull() != null) {
            // Enrolment never replaces an existing credential; that is what change() is for, and
            // it requires the current credential first.
            return failure(CredentialFailure.AlreadyConfigured)
        }
        return writeCredential(credential, confirmation)
    }

    private suspend fun changeLocked(
        current: CredentialInput,
        credential: CredentialInput,
        confirmation: CredentialInput,
    ): NivaraResult<Unit> {
        // The current credential is checked through the ordinary verification path, so the
        // attempt counter and the delay policy apply to credential changes as well.
        return when (val outcome = verifyLocked(current)) {
            is AuthenticationOutcome.Succeeded -> writeCredential(credential, confirmation)
            is AuthenticationOutcome.Failed -> failure(CredentialFailure.CurrentCredentialIncorrect)
            is AuthenticationOutcome.TemporarilyBlocked ->
                failure(CredentialFailure.TemporarilyBlocked(outcome.retryAfterMillis))
            is AuthenticationOutcome.NotConfigured -> failure(CredentialFailure.NotConfigured)
            is AuthenticationOutcome.InvalidConfiguration -> failure(CredentialFailure.InvalidConfiguration)
        }
    }

    /**
     * Validates the two entries, derives the credential key, and writes the record.
     *
     * The credential's canonical characters and the derived key are cleared here. The record is
     * the only thing that survives the call.
     */
    private suspend fun writeCredential(
        credential: CredentialInput,
        confirmation: CredentialInput,
    ): NivaraResult<Unit> {
        if (credential.type != confirmation.type) {
            return failure(CredentialFailure.ConfirmationTypeMismatch)
        }

        val canonical = canonicalForm(credential)
        val canonicalConfirmation = canonicalForm(confirmation)
        try {
            if (canonical == null || canonicalConfirmation == null) {
                return failure(CredentialFailure.PatternInvalid)
            }

            val rejected = policy.validate(credential.type, canonical)
            if (rejected != null) return failure(rejected)

            // A canonical comparison, so a pattern drawn with an extra touch on the same dot
            // still matches its confirmation.
            if (!constantTimeEquals(canonical, canonicalConfirmation)) {
                return failure(CredentialFailure.ConfirmationMismatch)
            }

            val config = keyDerivationService.config
            val salt = keyDerivationService.newSalt()
            val derived = keyDerivationService.deriveKey(canonical, salt, config)
            if (derived is NivaraResult.Failure) return failure(CredentialFailure.ProtectionFailed)
            val key = derived.valueOrNull() ?: return failure(CredentialFailure.ProtectionFailed)
            val verifier = computeVerifier(key, credential.type)
                ?: return failure(CredentialFailure.ProtectionFailed)

            val saved = try {
                store.save(
                    StoredCredential(
                        type = credential.type,
                        config = config,
                        // The salt is not secret: it is stored beside the record on purpose.
                        salt = salt.copyBytes(),
                        verifier = verifier.copyBytes(),
                    ),
                )
            } finally {
                verifier.clear()
            }

            return if (saved is NivaraResult.Failure) {
                NivaraResult.Failure(saved.error)
            } else {
                NivaraResult.Success(Unit)
            }
        } finally {
            releaseCanonical(credential, canonical)
            releaseCanonical(confirmation, canonicalConfirmation)
        }
    }

    // ------------------------------------------------------------------ verification

    private suspend fun verifyLocked(credential: CredentialInput): AuthenticationOutcome {
        val blocked = attemptTracker.remainingBlockMillis()
        if (blocked > 0L) return AuthenticationOutcome.TemporarilyBlocked(blocked)

        val loaded = store.load()
        if (loaded is NivaraResult.Failure) return AuthenticationOutcome.InvalidConfiguration
        val record = loaded.valueOrNull() ?: return AuthenticationOutcome.NotConfigured

        val canonical = canonicalForm(credential)
        try {
            if (canonical == null) return reject()
            if (policy.validate(credential.type, canonical) != null) return reject()

            val derived = keyDerivationService.deriveKey(
                password = canonical,
                salt = SensitiveBytes.of(record.salt),
                config = record.config,
            )
            // A derivation failure is a statement about the stored record, not about the
            // credential, so it does not count as a failed attempt.
            if (derived is NivaraResult.Failure) return AuthenticationOutcome.InvalidConfiguration
            val key = derived.valueOrNull() ?: return AuthenticationOutcome.InvalidConfiguration

            val computed = computeVerifier(key, credential.type)
                ?: return AuthenticationOutcome.InvalidConfiguration

            val matches = try {
                // Constant-time comparison of the stored verifier and the recomputed one.
                MessageDigest.isEqual(computed.unsafeByteArray(), record.verifier)
            } finally {
                computed.clear()
            }

            return if (matches) {
                attemptTracker.recordSuccess()
                AuthenticationOutcome.Succeeded
            } else {
                reject()
            }
        } finally {
            releaseCanonical(credential, canonical)
        }
    }

    /** Records a rejected attempt and reports the outcome, including the delay it triggered. */
    private suspend fun reject(): AuthenticationOutcome {
        attemptTracker.recordFailure()
        return AuthenticationOutcome.Failed(attemptTracker.remainingBlockMillis())
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun loadStatus(): NivaraResult<CredentialStatus> = store.load().fold(
        onSuccess = { credential ->
            NivaraResult.Success(
                if (credential == null) {
                    CredentialStatus.NotConfigured
                } else {
                    CredentialStatus.Configured(credential.type)
                },
            )
        },
        onFailure = { failure -> NivaraResult.Failure(failure.error) },
    )

    /**
     * Computes the verifier from a derived key and clears the key.
     *
     * Returns `null` when the key is not the in-process kind this layer expects, which would be
     * a programming error rather than a user-facing condition.
     */
    private fun computeVerifier(key: EncryptionKey, type: PrimaryCredentialType): SensitiveBytes? = try {
        val inProcess = key as? EncryptionKey.InProcess
        inProcess?.let { CredentialVerifier.compute(it.material, type) }
    } finally {
        (key as? EncryptionKey.InProcess)?.clear()
    }

    /**
     * The characters the credential is derived from: the input's own buffer for PIN and password,
     * or a new canonical buffer for a pattern.
     */
    private fun canonicalForm(input: CredentialInput): CharArray? = when (input) {
        is CredentialInput.Pin -> input.digits
        is CredentialInput.Password -> input.characters
        is CredentialInput.Pattern -> PatternCanonicalizer.canonicalize(input.points)
    }

    /** Clears a buffer created by [canonicalForm], never the caller's own input buffer. */
    private fun releaseCanonical(input: CredentialInput, canonical: CharArray?) {
        if (input is CredentialInput.Pattern) canonical?.fill(ZERO_CHAR)
    }

    /**
     * Compares two character buffers without stopping at the first difference.
     *
     * Used for the confirmation entry, where timing is not really a threat, and kept
     * constant-time so the same rule applies to every credential comparison in the layer.
     */
    private fun constantTimeEquals(first: CharArray, second: CharArray): Boolean {
        if (first.size != second.size) return false
        var difference = 0
        for (index in first.indices) {
            difference = difference or (first[index].code xor second[index].code)
        }
        return difference == 0
    }

    private fun failure(cause: CredentialFailure): NivaraResult<Unit> = NivaraResult.Failure(cause)

    private companion object {
        const val ZERO_CHAR: Char = '\u0000'
    }
}
