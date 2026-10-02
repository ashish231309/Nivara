package com.nivara.app.data.credential

import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.SensitiveBytes
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets

/**
 * Turns a credential-derived key into the one-way verifier Nivara stores.
 *
 * ### Why the derived key itself is not stored
 *
 * The key that PBKDF2 produces from the credential is the key that will later protect the vault
 * and the recovery envelope. Writing it to disk — even inside a credential record — would turn a
 * file that *can be used to check a guess* into a file that *is the key*. So the record stores
 * `HMAC-SHA-256(derivedKey, label ‖ type)` instead: a value that cannot be reversed into the key
 * and that reveals nothing about the credential beyond what a password hash already does.
 *
 * Verification is then: derive the key from what the user entered with the stored parameters,
 * recompute this value and compare. Brute-forcing a candidate credential costs exactly one
 * PBKDF2 derivation, because the HMAC is fast — the work factor that protects the credential is
 * the key derivation, which is what the parameter set is for.
 *
 * ### The label
 *
 * The label separates this use of the derived key from every other use, and it includes the
 * credential type, so a record cannot be relabelled from one method to another and still verify.
 * A wrong-type credential therefore fails the same way a wrong credential does, which is the
 * intended, indistinguishable outcome.
 *
 * Domain separation follows the same principle as the sealed-key container in the
 * cryptographic layer: the same key material must never be used for two purposes.
 */
internal object CredentialVerifier {

    /** HMAC-SHA-256, from the platform provider, available on every supported Android version. */
    private const val ALGORITHM = "HmacSHA256"

    /** Versioned domain-separation label. Changing the construction means changing this label. */
    private const val LABEL = "nivara.credential.verifier.v1"

    private const val TYPE_SEPARATOR: Byte = 0x00

    /**
     * Computes the verifier for [type] from [keyMaterial].
     *
     * [keyMaterial] belongs to the caller, which clears it. The returned value must be cleared
     * after it has been compared or written.
     */
    fun compute(keyMaterial: SensitiveBytes, type: PrimaryCredentialType): SensitiveBytes {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(keyMaterial.unsafeByteArray(), ALGORITHM))
        mac.update(LABEL.toByteArray(Charsets.US_ASCII))
        mac.update(TYPE_SEPARATOR)
        mac.update(type.name.toByteArray(Charsets.US_ASCII))
        return SensitiveBytes.wrap(mac.doFinal())
    }
}
