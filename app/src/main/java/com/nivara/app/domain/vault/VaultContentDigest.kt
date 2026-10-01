package com.nivara.app.domain.vault

/**
 * The digest of an encrypted object, as stored in the index.
 *
 * SHA-256 over the bytes of the encrypted object exactly as they live in the vault. It is what makes
 * "the file was imported" a statement about the bytes on storage rather than about a write call that
 * returned: the digest is computed while the object is written, recorded in the index only after it
 * has been read back and matched, and can be re-checked at any time without a key.
 *
 * It is not a secret and not a key, and it authenticates nothing on its own — the object's own
 * authentication tags do that. A digest beside the bytes it describes can be rewritten by anyone who
 * can rewrite both, which is why the index that carries it is itself authenticated.
 *
 * @property value thirty-two bytes, lower-case hexadecimal.
 */
data class VaultContentDigest(val value: String) {

    init {
        require(isWellFormed(value)) { "a content digest is ${LENGTH_CHARACTERS} hexadecimal characters" }
    }

    /** Hex, which is also the form the codec writes: two equal digests have equal text. */
    override fun toString(): String = value

    companion object {

        /** Bytes of SHA-256. */
        const val BYTES: Int = 32

        private const val LENGTH_CHARACTERS = BYTES * 2

        private const val HEX_DIGITS = "0123456789abcdef"

        /** Whether [value] is exactly one digest and nothing else. */
        fun isWellFormed(value: String): Boolean =
            value.length == LENGTH_CHARACTERS && value.all { character -> character in HEX_DIGITS }

        /** The digest of [bytes], which are thirty-two bytes of SHA-256 output and nothing else. */
        fun fromBytes(bytes: ByteArray): VaultContentDigest? =
            if (bytes.size == BYTES) VaultContentDigest(bytes.toHex()) else null

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
