package com.nivara.app.domain.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A byte buffer that holds secret material — key bytes, derived keys, recovery material.
 *
 * What this type actually gives you:
 *
 * - a single obvious place where secret bytes live, so review can follow them;
 * - an explicit [clear] to overwrite the buffer as soon as the value is no longer needed;
 * - a redacted [toString], because a `ByteArray` printed by accident would leak the secret into
 *   a log, a crash report or a bug tracker.
 *
 * What this type does **not** give you:
 *
 * - guaranteed erasure. This is a documented limitation, not an oversight. On the JVM/ART the
 *   garbage collector may copy an array during a compaction, a JIT may keep a value in a
 *   register or on the stack, and the platform may have swapped the page out before [clear]
 *   ran. Only the live buffer is overwritten; copies that already exist elsewhere (for example
 *   inside a `javax.crypto.SecretKeySpec`) are out of reach.
 * - protection from a compromised device or a privileged attacker. Nivara defends against
 *   other apps, casual access and offline analysis of backups — not against root.
 *
 * Therefore: keep the lifetime of any secret short, call [clear] as soon as possible, and never
 * turn secret bytes into a `String`, which is immutable and cannot be cleared at all.
 */
class SensitiveBytes private constructor(private val bytes: ByteArray) {

    /** Number of bytes held. */
    val size: Int get() = bytes.size

    /** `true` when every byte is zero, i.e. the buffer has been cleared (or started empty). */
    val isCleared: Boolean get() = bytes.all { it == ZERO_BYTE }

    /**
     * The live buffer.
     *
     * This intentionally breaks encapsulation: JCA APIs (`SecretKeySpec`, `Cipher`,
     * `Mac`, `PBEKeySpec`) take `ByteArray`s, and copying the secret into a second array just
     * to "protect" it would double the exposure without protecting anything. Callers must not
     * retain, copy into `String`, or log the returned array.
     */
    fun unsafeByteArray(): ByteArray = bytes

    /** A detached copy that the caller owns and must clear when finished. */
    fun copyBytes(): ByteArray = bytes.copyOf()

    /** Overwrites the buffer with zeroes. Safe to call more than once. */
    fun clear() {
        bytes.fill(ZERO_BYTE)
    }

    /**
     * Compares two secrets without leaking how many leading bytes matched.
     *
     * This is the only supported way to compare secrets. `==` compares references, so a
     * mistaken equality check cannot silently turn into a content comparison.
     *
     * [MessageDigest.isEqual] is the platform's constant-time comparison; a plain `contentEquals`
     * would short-circuit on the first differing byte and leak information through timing.
     * Lengths are compared first, which is unavoidable and not secret-dependent.
     */
    fun contentEquals(other: SensitiveBytes): Boolean =
        bytes.size == other.bytes.size && MessageDigest.isEqual(bytes, other.bytes)

    // equals/hashCode are intentionally not overridden. Secrecy is not identity: two buffers that
    // hold the same bytes are still two buffers, and a content-derived hashCode would put
    // secret-derived data into hash tables while making accidental non-constant-time comparisons
    // look legitimate. Use contentEquals to compare values deliberately.

    /** Redacted on purpose: `SensitiveBytes(size=32, content=REDACTED)`. */
    override fun toString(): String = "SensitiveBytes(size=$size, content=REDACTED)"

    companion object {
        private const val ZERO_BYTE: Byte = 0

        /** Copies [bytes] into a new secret buffer. The source array can be cleared by the caller. */
        fun of(bytes: ByteArray): SensitiveBytes = SensitiveBytes(bytes.copyOf())

        /**
         * Takes ownership of [bytes] without copying.
         *
         * Only for buffers that were just produced for this purpose and are not referenced
         * anywhere else. The caller must not touch the array afterwards.
         */
        fun wrap(bytes: ByteArray): SensitiveBytes = SensitiveBytes(bytes)

    }
}

/**
 * Cryptographically secure random values.
 *
 * This is the only source of randomness permitted in Nivara's security code. Keys, nonces, IVs
 * and salts all come from here: `java.util.Random`, timestamps, counters, hardware identifiers
 * and hard-coded values are never acceptable for cryptographic use.
 *
 * The underlying [SecureRandom] comes from the platform's strongest available provider
 * (`/dev/urandom` backed on Android, `SecureRandom` on the JVM) and seeds itself, so no seeding
 * is done here.
 *
 * The generator is thread safe because [SecureRandom] is, so one instance can be shared through
 * the application container.
 */
class SecureRandomGenerator(private val random: SecureRandom = SecureRandom()) {

    /** Generates [size] secret bytes, for example a 32-byte key. */
    fun nextBytes(size: Int): SensitiveBytes {
        require(size > 0) { "secret size must be positive but was $size" }
        val buffer = ByteArray(size)
        random.nextBytes(buffer)
        return SensitiveBytes.wrap(buffer)
    }

    /** Generates [size] bytes for values that are not themselves secret, such as a nonce or salt. */
    fun nextByteArray(size: Int): ByteArray {
        require(size > 0) { "random length must be positive but was $size" }
        return ByteArray(size).also { random.nextBytes(it) }
    }

    /** A 256-bit secret, the size used for content keys and recovery keys. */
    fun nextKeyBytes(): SensitiveBytes = nextBytes(KEY_SIZE_BYTES)

    override fun toString(): String = "SecureRandomGenerator(provider=${random.provider.name}, algorithm=${random.algorithm})"

    companion object {
        /** Nivara uses 256-bit symmetric keys everywhere. */
        const val KEY_SIZE_BYTES: Int = 32
    }
}
