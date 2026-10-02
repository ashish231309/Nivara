package com.nivara.app.domain.vault

import com.nivara.app.domain.security.SecureRandomGenerator

/**
 * The stable identifier of one album.
 *
 * An album is named by this and never by its title: a title is something a person types, changes
 * their mind about and can repeat, so it is a label rather than an identity. Two albums may be called
 * "Trip" and still be two albums, and renaming one changes nothing about what it contains.
 *
 * The shape is the same as every other Nivara identifier — 128 random bits, lower-case hexadecimal,
 * created once and never reused — but it is a type of its own so that an album can never be passed
 * where an item is expected, or the reverse. It says nothing about the album it names.
 *
 * @property value sixteen random bytes, lower-case hexadecimal.
 */
data class VaultAlbumId(val value: String) {

    init {
        require(VaultAlbumId.isWellFormed(value)) {
            "an album id is ${LENGTH_CHARACTERS} hexadecimal characters"
        }
    }

    /** Redacted like every other value that exists only to be compared. */
    override fun toString(): String = "VaultAlbumId(value=REDACTED)"

    companion object {

        /** Bytes of randomness behind an album id. */
        const val BYTES: Int = 16

        private const val LENGTH_CHARACTERS = BYTES * 2

        private const val HEX_DIGITS = "0123456789abcdef"

        /** Whether [value] is exactly one album id and nothing else. */
        fun isWellFormed(value: String): Boolean =
            value.length == LENGTH_CHARACTERS && value.all { character -> character in HEX_DIGITS }

        /**
         * Reads an album id from stored bytes, or `null` when they are not one.
         *
         * Returning `null` rather than throwing keeps stored data where it belongs: untrusted input
         * whose reader decides what a bad record means, rather than an exception to catch.
         */
        fun fromBytes(bytes: ByteArray): VaultAlbumId? =
            if (bytes.size == BYTES) VaultAlbumId(bytes.toHex()) else null

        /** A fresh identifier from platform randomness. */
        fun create(random: SecureRandomGenerator): VaultAlbumId =
            VaultAlbumId(random.nextByteArray(BYTES).toHex())

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

/**
 * The rules an album name must satisfy.
 *
 * An album name is never a path and never addresses anything — albums are not directories, and
 * nothing in the vault is named after a title — so the rules are about being a *label a person typed*
 * rather than about storage: it must be text, it must not be blank, it must not contain control
 * characters, and it must be bounded so a name cannot grow into a way to fill the metadata record.
 *
 * The bound is on encoded UTF-8 bytes at the codec, and on characters here, for the same reason the
 * item name has both: a name of emoji is short to read and long to store, and the record's own limit
 * has to hold whatever a person can type.
 */
object VaultAlbumNames {

    /** The longest album name Nivara will store, in characters. */
    const val MAXIMUM_LENGTH: Int = 80

    /**
     * Whether [name] is a usable album title.
     *
     * Rejected: empty or blank names, names longer than [MAXIMUM_LENGTH] characters, and names
     * containing a control character. A name that is only whitespace is refused rather than trimmed
     * into something the person did not type; [normalize] is what a caller uses to trim what it
     * accepts before asking.
     */
    fun isWellFormed(name: String): Boolean {
        if (name.isBlank() || name.length > MAXIMUM_LENGTH) return false
        return name.none { character -> character.isISOControl() }
    }

    /**
     * The name to store for what the person typed: surrounding whitespace removed, inner whitespace
     * collapsed to single spaces, or `null` when what is left is not a usable title.
     *
     * Collapsing inner runs of whitespace is not cosmetic: a name with a newline in it can be drawn as
     * two lines in a list, and a name with a tab can be drawn as something that is not what was
     * typed. What is stored is what is shown, so both are reduced to the one space a person meant.
     */
    fun normalize(name: String): String? {
        val collapsed = name.trim().replace(WHITESPACE_RUN, " ")
        return if (isWellFormed(collapsed)) collapsed else null
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
