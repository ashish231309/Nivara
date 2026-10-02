package com.nivara.app.domain.credential

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.security.KeyDerivationConfig

/**
 * Everything Nivara keeps on disk in order to verify a primary credential.
 *
 * The record holds no secret that can be used to authenticate:
 *
 * - [type] is which method is active;
 * - [config] and [salt] are the parameters the key derivation used — deliberately stored, so a
 *   later build can raise the work factor for new credentials while still reading an old one;
 * - [verifier] is a one-way value derived from the credential. It is *not* the derived key.
 *   The key itself is never written anywhere; see the credential design document.
 *
 * Because two of the fields are byte arrays, this is a plain class rather than a data class:
 * generated `equals` and `hashCode` would compare arrays by identity, and a content-comparing
 * `equals` would be a non-constant-time comparison sitting in the type system waiting to be used
 * for a credential check. Comparisons of [verifier] go through a deliberate constant-time
 * comparison in the implementing layer.
 *
 * Instances are validated on construction: a record that cannot be used is never handed to
 * callers in the first place.
 */
class StoredCredential(
    val type: PrimaryCredentialType,
    val config: KeyDerivationConfig,
    val salt: ByteArray,
    val verifier: ByteArray,
) {
    init {
        require(salt.size >= KeyDerivationConfig.MINIMUM_SALT_BYTES) {
            "salt must be at least ${KeyDerivationConfig.MINIMUM_SALT_BYTES} bytes"
        }
        require(verifier.isNotEmpty()) { "verifier cannot be empty" }
    }

    /** Non-secret summary. The verifier is redacted. */
    override fun toString(): String =
        "StoredCredential(type=${type.name}, iterations=${config.iterations}, " +
            "saltBytes=${salt.size}, verifier=REDACTED)"
}

/**
 * Where the single credential record lives.
 *
 * There is deliberately no `delete`, `clear` or `reset` here. Removing the primary credential
 * without authenticating would be a bypass, and an insecure reset is exactly the kind of
 * convenience that turns into a back door. Replacing the record goes through
 * [CredentialManager.change], which authenticates first; removal will only ever be possible
 * through the recovery mechanism designed in a later stage. Making that a property of the
 * interface — rather than a rule in a document — means a future caller cannot accidentally
 * ship a bypass.
 *
 * Implementations must never persist anything beyond [StoredCredential]: no credential, no
 * derived key, no copy of the verifier anywhere else.
 */
interface CredentialStore {

    /** The stored record, `null` when nothing is configured yet, or a typed failure. */
    suspend fun load(): NivaraResult<StoredCredential?>

    /** Writes [credential], replacing any previous record atomically. */
    suspend fun save(credential: StoredCredential): NivaraResult<Unit>
}

/**
 * Where the throttling counters live.
 *
 * Separate from [CredentialStore] because it holds a different kind of thing: a policy counter
 * that may be reset at any time, next to a record that must never be silently removed. Losing
 * the counters costs nothing but patience; losing the record would mean the user's credential
 * can no longer be verified.
 */
interface AttemptStore {

    /** The stored state, or [ThrottleState.Clear] when nothing has been recorded yet. */
    suspend fun load(): NivaraResult<ThrottleState>

    /** Writes [state], replacing any previous value atomically. */
    suspend fun save(state: ThrottleState): NivaraResult<Unit>
}
