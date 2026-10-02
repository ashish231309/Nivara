package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultTrashEntry
import com.nivara.app.domain.vault.VaultTrashLimits

/**
 * The vault's trash record, version 1.
 *
 * ```
 * trash.0.nvt
 * offset  0        4        5         6               14
 *         | "NVTR" | version| reserved | generation (8) | sealed payload |
 *
 * payload (plaintext, before the envelope seals it)
 * offset  0        4        5         6              14          16
 *         | "NVTR" | version| reserved | generation (8)| entry count (2) | entries…
 *
 * entry
 * offset  0                  16                       24
 *         | item id (16) | trashed at (8) |
 * ```
 *
 * ### The same shape as the records beside it, on purpose
 *
 * The record is built exactly like `index.N.nvi` and `albums.N.nva`: a clear header that names what
 * the bytes are and which generation they belong to, a payload sealed by the existing encryption
 * service, and a generation that appears in both places so that promoting an older record by editing
 * its header makes it invalid rather than authoritative. Three records that follow one pattern are one
 * thing to understand.
 *
 * ### Its own marker *and* its own purpose
 *
 * The marker is `NVTR`, not the index's `NVIN` or the album record's `NVAO`, and the payload is sealed
 * under
 * [`EncryptionContext.VaultTrash`][com.nivara.app.domain.security.EncryptionContext.VaultTrash] rather
 * than either of theirs. Either check alone would be enough — the context is authenticated, so a
 * ciphertext made for one purpose cannot be accepted for another — and both are kept because they
 * answer different questions: the marker says *what these bytes are* before anyone holds a key, and
 * the purpose says *what they were sealed for* before anything is written over.
 *
 * ### Nothing but references and moments
 *
 * An entry is an identifier and a timestamp. There is no name, no type, no size and no path in this
 * format, and therefore nothing in it that could disagree with the index a week later: what a trashed
 * file is called is read from the vault's own list at the moment it is shown.
 *
 * ### Parsing is bounded and strict
 *
 * Every count is checked against a bound before it is used to allocate anything, identifiers must be
 * exactly sixteen bytes of lower-case hex at canonical positions, the entries must be in strictly
 * increasing identifier order (which also makes a duplicate impossible to express), a timestamp before
 * the epoch is refused, and trailing bytes are refused rather than ignored. `null` never means "nothing
 * is trashed": a record with no entries is a valid record with a count of zero, and the difference
 * matters because an empty trash list must never be what a damaged record turns into.
 *
 * This codec holds no key and touches no cipher: it assembles and parses bytes, and the sealing belongs
 * to the encryption service.
 */
internal object VaultTrashCodec {

    /** "NVTR" — Nivara vault trash. Distinct from the index's and the album record's markers. */
    val MAGIC: ByteArray = "NVTR".toByteArray(Charsets.US_ASCII)

    /** The only format version this build writes. Anything else is refused, never reinterpreted. */
    const val VERSION: Int = 1

    const val ID_SIZE: Int = 16
    const val COUNT_SIZE: Int = 2
    const val TIME_SIZE: Int = 8
    const val GENERATION_SIZE: Int = 8

    /** One entry: the identifier and the instant it was trashed. */
    const val ENTRY_SIZE: Int = ID_SIZE + TIME_SIZE

    /** The clear header of a trash record: magic, version, reserved, generation. */
    const val HEADER_LENGTH: Int = 4 + 1 + 1 + GENERATION_SIZE

    /** Bytes of [MAGIC]. Kept beside it so the payload's header can be a constant expression. */
    const val MAGIC_LENGTH: Int = 4

    /** The payload's own header, before the entries. */
    const val PAYLOAD_HEADER_LENGTH: Int = MAGIC_LENGTH + 1 + 1 + GENERATION_SIZE + COUNT_SIZE

    /** The most entries one record can name, fixed by the two-byte count. */
    const val MAXIMUM_ENTRY_COUNT: Int = 65_535

    /** The first generation a trash record can have. */
    const val FIRST_GENERATION: Long = 1L

    /** What a clear header says about a blob of bytes, before anything is decrypted. */
    sealed interface HeaderRead {

        /** The bytes begin with Nivara's trash marker and carry a header this build can read. */
        data class Present(val generation: Long) : HeaderRead

        /** The bytes do not begin with the trash marker, so they are not a trash record at all. */
        data object NotATrashRecord : HeaderRead

        /**
         * The bytes begin with the marker and the header cannot be used: too short to hold one, a set
         * reserved byte, or a version this build does not know. The version travels when it could be
         * read, so a record from a newer Nivara is reported as such rather than as damage.
         */
        data class Unreadable(val fileVersion: Int?) : HeaderRead
    }

    /** A payload as read from a decrypted trash record. */
    data class Payload(val generation: Long, val entries: List<VaultTrashEntry>)

    /** Reads the clear header of [bytes] without decrypting anything. */
    fun readHeader(bytes: ByteArray): HeaderRead {
        if (bytes.size < MAGIC.size || !bytes.startsWith(MAGIC)) return HeaderRead.NotATrashRecord
        if (bytes.size < HEADER_LENGTH) {
            // The marker is there and the record is too short to be one: a Nivara file that was
            // damaged, which must not read as "nothing is in the trash".
            return HeaderRead.Unreadable(fileVersion = bytes.getOrNull(MAGIC.size)?.toInt()?.and(0xFF))
        }
        val version = bytes[MAGIC.size].toInt() and 0xFF
        val reserved = bytes[MAGIC.size + 1].toInt() and 0xFF
        if (reserved != 0) return HeaderRead.Unreadable(fileVersion = version)
        if (version != VERSION) return HeaderRead.Unreadable(fileVersion = version)
        val generation = readLong(bytes, MAGIC.size + 2)
        if (generation < FIRST_GENERATION) return HeaderRead.Unreadable(fileVersion = version)
        return HeaderRead.Present(generation = generation)
    }

    /** Whether [bytes] carries a readable header, so an envelope can be taken from it. */
    fun envelopeOf(bytes: ByteArray): ByteArray = bytes.copyOfRange(HEADER_LENGTH, bytes.size)

    /** Assembles a record around [envelope], which must already be sealed. */
    fun encodeRecord(generation: Long, envelope: ByteArray): ByteArray {
        require(generation >= FIRST_GENERATION) { "a generation starts at $FIRST_GENERATION" }
        require(envelope.isNotEmpty()) { "a record holds an envelope" }
        val buffer = ByteArray(HEADER_LENGTH + envelope.size)
        MAGIC.copyInto(buffer, 0)
        buffer[MAGIC.size] = VERSION.toByte()
        buffer[MAGIC.size + 1] = 0
        writeLong(buffer, MAGIC.size + 2, generation)
        envelope.copyInto(buffer, HEADER_LENGTH)
        return buffer
    }

    /**
     * The plaintext the envelope is to seal for [entries], or `null` when they cannot be encoded.
     *
     * Returning `null` rather than throwing keeps "this cannot be written" at the call site, where the
     * typed failure is. Every rule the reader enforces is enforced here too — the writer must never
     * produce a record its own reader would refuse — which is why this validates identifiers,
     * timestamps, duplicates and the record's size rather than trusting its caller.
     *
     * The entries are written in ascending identifier order, so one set of entries has exactly one
     * encoding and a read-back can be compared byte for byte.
     */
    fun encodePayload(generation: Long, entries: List<VaultTrashEntry>): ByteArray? {
        if (generation < FIRST_GENERATION) return null
        if (entries.size > VaultTrashLimits.MAXIMUM_TRASHED_ITEMS) return null
        if (entries.size > MAXIMUM_ENTRY_COUNT) return null
        if (entries.map { entry -> entry.itemId }.distinct().size != entries.size) return null

        val total = PAYLOAD_HEADER_LENGTH + entries.size * ENTRY_SIZE
        if (total > VaultTrashLimits.MAXIMUM_RECORD_BYTES) return null

        val ordered = entries.sortedBy { entry -> entry.itemId.value }

        val buffer = ByteArray(total)
        MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        writeLong(buffer, 6, generation)
        writeShort(buffer, 14, entries.size)
        var offset = PAYLOAD_HEADER_LENGTH
        for (entry in ordered) {
            val idBytes = hexToBytes(entry.itemId.value) ?: return null
            if (entry.trashedAtEpochMillis < 0) return null
            idBytes.copyInto(buffer, offset)
            offset += ID_SIZE
            writeLong(buffer, offset, entry.trashedAtEpochMillis)
            offset += TIME_SIZE
        }
        return buffer
    }

    /**
     * Parses a decrypted payload, or returns `null` when it is not one this build wrote.
     *
     * @param expectedGeneration the generation the clear header carried. A payload that disagrees with
     *   its own header belongs to a record somebody edited, and is refused.
     */
    fun decodePayload(bytes: ByteArray, expectedGeneration: Long): Payload? {
        if (bytes.size < PAYLOAD_HEADER_LENGTH || !bytes.startsWith(MAGIC)) return null
        if ((bytes[4].toInt() and 0xFF) != VERSION) return null
        if ((bytes[5].toInt() and 0xFF) != 0) return null
        val generation = readLong(bytes, 6)
        if (generation != expectedGeneration) return null
        if (generation < FIRST_GENERATION) return null

        val count = readShort(bytes, 14) ?: return null
        // Checked before anything is sized from it: the count is a claim made by the bytes, not a fact.
        if (count > VaultTrashLimits.MAXIMUM_TRASHED_ITEMS || count > MAXIMUM_ENTRY_COUNT) return null
        if (PAYLOAD_HEADER_LENGTH + count * ENTRY_SIZE > bytes.size) return null

        val entries = ArrayList<VaultTrashEntry>(count)
        var offset = PAYLOAD_HEADER_LENGTH
        var previous: String? = null
        repeat(count) {
            val itemId = VaultItemId.fromBytes(bytes.copyOfRange(offset, offset + ID_SIZE)) ?: return null
            offset += ID_SIZE
            val trashedAt = readLong(bytes, offset)
            offset += TIME_SIZE
            if (trashedAt < 0) return null
            // Strictly increasing identifiers: a record naming one item twice, or written in some
            // other order, is refused rather than accepted as a second spelling of the same set.
            if (previous != null && itemId.value <= previous) return null
            previous = itemId.value
            entries += VaultTrashEntry(itemId = itemId, trashedAtEpochMillis = trashedAt)
        }
        // Nothing may follow the last entry: trailing bytes would mean the payload is not the one this
        // codec wrote, and accepting them would make the format ambiguous.
        if (offset != bytes.size) return null
        return Payload(generation = generation, entries = entries)
    }

    /** The sixteen bytes of a lower-case hex identifier, or `null` when it is not one. */
    private fun hexToBytes(value: String): ByteArray? {
        if (value.length != ID_SIZE * 2) return null
        val bytes = ByteArray(ID_SIZE)
        for (index in bytes.indices) {
            val high = value[index * 2].digitToIntOrNull(radix = 16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(radix = 16) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int? {
        if (offset + COUNT_SIZE > bytes.size) return null
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    private fun writeLong(buffer: ByteArray, offset: Int, value: Long) {
        for (index in 0 until GENERATION_SIZE) {
            buffer[offset + index] = (value ushr ((GENERATION_SIZE - 1 - index) * 8)).toByte()
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until GENERATION_SIZE) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        return value
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
