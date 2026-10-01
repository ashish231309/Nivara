package com.nivara.app.domain.vault

import com.nivara.app.domain.security.SecureRandomGenerator

/**
 * The non-secret identifier of a vault.
 *
 * Created once, when the vault is initialized, and written into its metadata. It answers "is this
 * the same vault?" for later stages — a recovery card, an index record, a reconnection — without
 * being a secret and without being a location:
 *
 * * it is **not a key** and not derived from one; publishing it reveals nothing about the vault's
 *   contents and does not weaken anything;
 * * it is **not a path**: a vault can be moved, recopied or restored to another folder and keeps its
 *   identity, and two folders holding copies of the same vault have the same identity, which is
 *   exactly what a later reconnection needs to know;
 * * it is **not personal data**: 128 random bits, meaningless on their own.
 *
 * Even so, Nivara treats it as something that is not printed: it never reaches a log, a message or a
 * screen, because an identifier that appears in output is an identifier that can be correlated, and
 * nothing in the product needs to display it.
 *
 * @property value sixteen random bytes, lower-case hexadecimal.
 */
data class VaultIdentity(val value: String) {

    init {
        require(value.length == LENGTH_CHARACTERS) {
            "a vault identity is ${LENGTH_CHARACTERS} hexadecimal characters"
        }
        require(value.all { character -> character in HEX_DIGITS }) {
            "a vault identity is hexadecimal"
        }
    }

    /** Redacted like every other value that exists only to be compared. */
    override fun toString(): String = "VaultIdentity(value=REDACTED)"

    companion object {

        /** Bytes of randomness behind an identity. */
        const val BYTES: Int = 16

        private const val LENGTH_CHARACTERS = BYTES * 2

        private val HEX_DIGITS: Set<Char> = ('0'..'9').toSet() + ('a'..'f').toSet()

        /**
         * Creates an identity from platform randomness.
         *
         * The only permitted source: the vault identifier is compared and stored, so it must never be
         * derived from a clock, a counter or anything else that can be predicted or collide.
         */
        fun create(random: SecureRandomGenerator): VaultIdentity =
            VaultIdentity(random.nextByteArray(BYTES).toHex())

        /**
         * Reads an identity from stored bytes, or `null` when they are not a valid one.
         *
         * Returning `null` rather than throwing is deliberate: stored data is untrusted input, and a
         * caller that has to decide what a bad record means should not have to catch an exception to
         * find out. Nothing is guessed — an identity that is not exactly right is not an identity.
         */
        fun fromBytes(bytes: ByteArray): VaultIdentity? =
            if (bytes.size == BYTES) VaultIdentity(bytes.toHex()) else null

        /** Whether [value] is exactly one vault identity and nothing else. */
        fun isWellFormed(value: String): Boolean =
            value.length == LENGTH_CHARACTERS && value.all { character -> character in HEX_DIGITS }

        private fun ByteArray.toHex(): String {
            val digits = "0123456789abcdef"
            val builder = StringBuilder(size * 2)
            for (byte in this) {
                val value = byte.toInt() and 0xFF
                builder.append(digits[value ushr 4])
                builder.append(digits[value and 0x0F])
            }
            return builder.toString()
        }
    }
}
