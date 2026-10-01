package com.nivara.app.domain.vault

import com.nivara.app.domain.security.SecureRandomGenerator

/**
 * The stable identifier of one imported item.
 *
 * Created once, when a file is imported, and never reused or changed: the item's encrypted object is
 * named after it, the index is keyed by it, and later stages will refer to stored items by it. It is
 * 128 random bits, so two imports cannot collide, and it says nothing about the file it names — not
 * its name, not its type, and not where it came from.
 *
 * Like every other identifier in Nivara it is compared and never printed: it is not a secret, but an
 * identifier that appears in output is an identifier that can be correlated, and nothing in the
 * product needs to show one.
 *
 * @property value sixteen random bytes, lower-case hexadecimal.
 */
data class VaultItemId(val value: String) {

    init {
        require(VaultItemId.isWellFormed(value)) { "an item id is ${LENGTH_CHARACTERS} hexadecimal characters" }
    }

    /** Redacted like every other value that exists only to be compared. */
    override fun toString(): String = "VaultItemId(value=REDACTED)"

    /** The identifier as the sixteen bytes a content stream is bound to. */
    fun toBytes(): ByteArray = ByteArray(BYTES) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
    }

    companion object {

        /** Bytes of randomness behind an item id. */
        const val BYTES: Int = 16

        private const val LENGTH_CHARACTERS = BYTES * 2

        private const val HEX_DIGITS = "0123456789abcdef"

        /** Whether [value] is exactly one item id and nothing else. */
        fun isWellFormed(value: String): Boolean =
            value.length == LENGTH_CHARACTERS &&
                value.all { character -> character in HEX_DIGITS }

        /**
         * Reads an item id from stored bytes, or `null` when they are not one.
         *
         * Returning `null` rather than throwing is deliberate: stored data is untrusted input, and a
         * caller deciding what a bad record means should not have to catch an exception to find out.
         */
        fun fromBytes(bytes: ByteArray): VaultItemId? =
            if (bytes.size == BYTES) VaultItemId(bytes.toHex()) else null

        /**
         * A fresh identifier from platform randomness.
         *
         * The only permitted source: an identifier that can collide would make two imported files
         * share one encrypted object.
         */
        fun create(random: SecureRandomGenerator): VaultItemId =
            VaultItemId(random.nextByteArray(BYTES).toHex())

        internal fun ByteArray.toHex(): String {
            val builder = StringBuilder(size * 2)
            for (byte in this) {
                val value = byte.toInt() and 0xFF
                builder.append(HEX_DIGITS[value ushr 4])
                builder.append(HEX_DIGITS[value and 0x0F])
            }
            return builder.toString()
        }
    }
}
