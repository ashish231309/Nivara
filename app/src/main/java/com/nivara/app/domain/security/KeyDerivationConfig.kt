package com.nivara.app.domain.security

/**
 * Password-based key derivation algorithms Nivara can use.
 *
 * Only PBKDF2-HMAC-SHA-256 is enabled. It is available on every supported Android version
 * through the platform provider, which keeps API 28 support honest — a newer memory-hard KDF
 * (Argon2id) would need a bundled native library, larger attack surface and its own review, so
 * it is a deliberate future decision rather than something smuggled into this stage.
 */
enum class KeyDerivationAlgorithm(
    /** Stable identifier, part of any persisted parameter set. */
    val id: Int,
    /**
     * Lowest iteration count Nivara accepts for this algorithm. Counts below this are refused
     * rather than accepted with a warning, so stored parameters cannot be downgraded silently.
     */
    val minimumIterations: Int,
) {
    Pbkdf2HmacSha256(id = 1, minimumIterations = 100_000),
}

/**
 * Parameters for deriving a key from a credential.
 *
 * These values are stored next to the data they protect, so they are versioned by construction:
 * [algorithm] and [iterations] travel with the ciphertext, and a later build can raise the cost
 * for new data while still reading old data with its recorded parameters.
 *
 * Construction validates the parameters. An unusable parameter set is a programming or storage
 * error and must not reach the KDF, where it could only produce a weaker key.
 */
data class KeyDerivationConfig(
    val algorithm: KeyDerivationAlgorithm,
    val saltBytes: Int,
    val iterations: Int,
    val keySizeBits: Int,
    val version: Int = CURRENT_VERSION,
) {
    init {
        require(saltBytes >= MINIMUM_SALT_BYTES) {
            "salt must be at least $MINIMUM_SALT_BYTES bytes but was $saltBytes"
        }
        require(iterations >= algorithm.minimumIterations) {
            "iterations must be at least ${algorithm.minimumIterations} for ${algorithm.name} but was $iterations"
        }
        require(keySizeBits == SUPPORTED_KEY_SIZE_BITS) {
            "only $SUPPORTED_KEY_SIZE_BITS-bit derived keys are supported but was $keySizeBits"
        }
        require(version >= MINIMUM_VERSION) {
            "unsupported parameter version $version"
        }
    }

    override fun toString(): String =
        "KeyDerivationConfig(algorithm=${algorithm.name}, saltBytes=$saltBytes, " +
            "iterations=$iterations, keySizeBits=$keySizeBits, version=$version)"

    companion object {
        /** Current version of the parameter set. */
        const val CURRENT_VERSION: Int = 1

        private const val MINIMUM_VERSION: Int = 1

        /** 128 bits of salt; unique per credential. */
        const val MINIMUM_SALT_BYTES: Int = 16

        /** Nivara derives AES-256 keys. */
        const val SUPPORTED_KEY_SIZE_BITS: Int = 256

        /** PBKDF2-HMAC-SHA-256 iterations, following OWASP's current recommendation. */
        const val DEFAULT_ITERATIONS: Int = 600_000

        /**
         * Parameters used for new data: PBKDF2-HMAC-SHA-256, 600 000 iterations, 128-bit salt,
         * 256-bit derived key.
         *
         * Cost check: 600 000 iterations is roughly half a second on a mid-range phone. Derivation
         * therefore runs on a background dispatcher and is only performed when a credential is
         * actually being verified or a key is being unlocked — never on the main thread, and never
         * per file (that is what the content-key layer is for).
         */
        val Pbkdf2HmacSha256Default: KeyDerivationConfig = KeyDerivationConfig(
            algorithm = KeyDerivationAlgorithm.Pbkdf2HmacSha256,
            saltBytes = MINIMUM_SALT_BYTES,
            iterations = DEFAULT_ITERATIONS,
            keySizeBits = SUPPORTED_KEY_SIZE_BITS,
        )
    }
}
