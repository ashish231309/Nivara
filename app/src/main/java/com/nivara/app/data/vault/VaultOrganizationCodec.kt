package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultAlbum
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultAlbumNames
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultOrganizationLimits
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The vault's album record, version 1.
 *
 * ```
 * albums.0.nva
 * offset  0        4        5         6               14
 *         | "NVAO" | version| reserved | generation (8) | sealed payload |
 *
 * payload (plaintext, before the envelope seals it)
 * offset  0        4        5         6              14          16
 *         | "NVAO" | version| reserved | generation (8)| album count (2) | albums… |
 *
 * album
 * offset  0       16       18           18 + name | +8 | +2 | members (16 each)
 *         | id (16) | name length (2) | name (utf-8) | created at (8) | member count (2) | member ids… |
 * ```
 *
 * ### The same shape as the index, on purpose
 *
 * The record is built exactly like `index.N.nvi`: a clear header that names what the bytes are and
 * which generation they belong to, a payload sealed by the existing encryption service, and a
 * generation that appears in both places so that promoting an older record by editing its header
 * makes it invalid rather than authoritative. Two records that follow one pattern are one thing to
 * understand, and the reader of either can recognise the other's rules.
 *
 * ### Its own marker *and* its own purpose
 *
 * The marker is `NVAO`, not the index's `NVIN`, and the payload is sealed under
 * [`EncryptionContext.VaultOrganization`][com.nivara.app.domain.security.EncryptionContext.VaultOrganization]
 * rather than the index's purpose. Either check alone would be enough — the context is authenticated,
 * so a ciphertext made for one purpose cannot be accepted for the other — and both are kept because
 * they answer different questions: the marker says *what these bytes are* before anyone holds a key,
 * and the purpose says *what they were sealed for* before anything is written over.
 *
 * ### Parsing is bounded and strict
 *
 * Every count is checked against a bound before it is used to allocate anything, every length is
 * checked before it is read, names must be valid album titles, ids must be exactly sixteen bytes of
 * lower-case hex, a duplicate album id or a duplicate membership makes the whole record invalid, text
 * that is not valid UTF-8 is refused rather than replaced, and trailing bytes are refused rather than
 * ignored. `null` never means "no albums": a record with no albums is a valid record with a count of
 * zero, and the difference matters because an empty album list must never be what a damaged record
 * turns into.
 *
 * This codec holds no key and touches no cipher: it assembles and parses bytes, and the sealing
 * belongs to the encryption service.
 */
internal object VaultOrganizationCodec {

    /** "NVAO" — Nivara vault albums/organization. Distinct from the index's marker. */
    val MAGIC: ByteArray = "NVAO".toByteArray(Charsets.US_ASCII)

    /** The only format version this build writes. Anything else is refused, never reinterpreted. */
    const val VERSION: Int = 1

    const val ID_SIZE: Int = 16
    const val LENGTH_SIZE: Int = 2
    const val COUNT_SIZE: Int = 2
    const val TIME_SIZE: Int = 8
    const val GENERATION_SIZE: Int = 8

    /** The clear header of an album record: magic, version, reserved, generation. */
    const val HEADER_LENGTH: Int = 4 + 1 + 1 + GENERATION_SIZE

    /** Bytes of [MAGIC]. Kept beside it so the payload's header can be a constant expression. */
    const val MAGIC_LENGTH: Int = 4

    /** The payload's own header, before the albums. */
    const val PAYLOAD_HEADER_LENGTH: Int = MAGIC_LENGTH + 1 + 1 + GENERATION_SIZE + COUNT_SIZE

    /** The most albums one record can name, fixed by the two-byte count. */
    const val MAXIMUM_ALBUM_COUNT: Int = 65_535

    /** What a clear header says about a blob of bytes, before anything is decrypted. */
    sealed interface HeaderRead {

        /** The bytes begin with Nivara's albums marker and carry a header this build can read. */
        data class Present(val generation: Long) : HeaderRead

        /** The bytes do not begin with the albums marker, so they are not an album record at all. */
        data object NotAnAlbumRecord : HeaderRead

        /**
         * The bytes begin with the marker and the header cannot be used: too short to hold one, a set
         * reserved byte, or a version this build does not know. The version travels when it could be
         * read, so a record from a newer Nivara is reported as such rather than as damage.
         */
        data class Unreadable(val fileVersion: Int?) : HeaderRead
    }

    /** A payload as read from a decrypted album record. */
    data class Payload(val generation: Long, val albums: List<VaultAlbum>)

    /** Reads the clear header of [bytes] without decrypting anything. */
    fun readHeader(bytes: ByteArray): HeaderRead {
        if (bytes.size < MAGIC.size || !bytes.startsWith(MAGIC)) return HeaderRead.NotAnAlbumRecord
        if (bytes.size < HEADER_LENGTH) {
            // The marker is there and the record is too short to be one: a Nivara file that was
            // damaged, which must not read as "no albums yet".
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
     * The plaintext the envelope is to seal for [albums], or `null` when they cannot be encoded.
     *
     * Returning `null` rather than throwing keeps "this cannot be written" at the call site, where the
     * typed failure is. Every rule the reader enforces is enforced here too — the writer must never
     * produce a record its own reader would refuse — which is why this validates names, identifier
     * shapes, duplicates and the record's size rather than trusting its caller.
     */
    fun encodePayload(generation: Long, albums: List<VaultAlbum>): ByteArray? {
        if (generation < FIRST_GENERATION) return null
        if (albums.size > VaultOrganizationLimits.MAXIMUM_ALBUMS) return null
        if (albums.map { album -> album.id }.distinct().size != albums.size) return null

        var totalMemberships = 0
        val encoded = ArrayList<ByteArray>(albums.size)
        var total = PAYLOAD_HEADER_LENGTH
        for (album in albums) {
            val bytes = encodeAlbum(album) ?: return null
            totalMemberships += album.itemIds.size
            if (totalMemberships > VaultOrganizationLimits.MAXIMUM_TOTAL_MEMBERSHIPS) return null
            total += bytes.size
            if (total > VaultOrganizationLimits.MAXIMUM_RECORD_BYTES) return null
            encoded += bytes
        }

        val buffer = ByteArray(total)
        MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        writeLong(buffer, 6, generation)
        writeShort(buffer, 14, albums.size)
        var offset = PAYLOAD_HEADER_LENGTH
        for (bytes in encoded) {
            bytes.copyInto(buffer, offset)
            offset += bytes.size
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
        if (count > VaultOrganizationLimits.MAXIMUM_ALBUMS || count > MAXIMUM_ALBUM_COUNT) return null

        val albums = ArrayList<VaultAlbum>(count)
        val seenAlbums = HashSet<VaultAlbumId>(count)
        var totalMemberships = 0
        var offset = PAYLOAD_HEADER_LENGTH
        repeat(count) {
            val decoded = decodeAlbum(bytes, offset) ?: return null
            val album = decoded.first
            offset = decoded.second
            if (!seenAlbums.add(album.id)) return null
            totalMemberships += album.itemIds.size
            if (totalMemberships > VaultOrganizationLimits.MAXIMUM_TOTAL_MEMBERSHIPS) return null
            albums += album
        }
        // Nothing may follow the last album: trailing bytes would mean the payload is not the one this
        // codec wrote, and accepting them would make the format ambiguous.
        if (offset != bytes.size) return null
        return Payload(generation = generation, albums = albums)
    }

    /** One encoded album: identifier, title, creation time and member identifiers. */
    private fun encodeAlbum(album: VaultAlbum): ByteArray? {
        val nameBytes = album.name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > VaultOrganizationLimits.MAXIMUM_NAME_BYTES) return null
        if (!VaultAlbumNames.isWellFormed(album.name)) return null
        if (album.createdAtEpochMillis < 0) return null
        if (album.itemIds.size > VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM) return null
        if (album.itemIds.distinct().size != album.itemIds.size) return null
        if (!VaultAlbumId.isWellFormed(album.id.value)) return null

        val size = ID_SIZE + LENGTH_SIZE + nameBytes.size + TIME_SIZE + COUNT_SIZE +
            album.itemIds.size * ID_SIZE
        // The largest one album can be, checked before it is allocated.
        if (size > VaultOrganizationLimits.MAXIMUM_RECORD_BYTES) return null

        val albumIdBytes = hexToBytes(album.id.value) ?: return null

        val buffer = ByteArray(size)
        var offset = 0
        albumIdBytes.copyInto(buffer, offset)
        offset += ID_SIZE
        writeShort(buffer, offset, nameBytes.size)
        offset += LENGTH_SIZE
        nameBytes.copyInto(buffer, offset)
        offset += nameBytes.size
        writeLong(buffer, offset, album.createdAtEpochMillis)
        offset += TIME_SIZE
        writeShort(buffer, offset, album.itemIds.size)
        offset += COUNT_SIZE
        for (itemId in album.itemIds) {
            val idBytes = hexToBytes(itemId.value) ?: return null
            idBytes.copyInto(buffer, offset)
            offset += ID_SIZE
        }
        return buffer
    }

    /** One decoded album and the offset after it. */
    private fun decodeAlbum(bytes: ByteArray, start: Int): Pair<VaultAlbum, Int>? {
        var offset = start
        if (offset + ID_SIZE > bytes.size) return null
        val albumId = VaultAlbumId.fromBytes(bytes.copyOfRange(offset, offset + ID_SIZE)) ?: return null
        offset += ID_SIZE

        val name = readBoundedText(bytes, offset, VaultOrganizationLimits.MAXIMUM_NAME_BYTES) ?: return null
        offset = name.second
        if (!VaultAlbumNames.isWellFormed(name.first)) return null

        if (offset + TIME_SIZE + COUNT_SIZE > bytes.size) return null
        val createdAt = readLong(bytes, offset)
        offset += TIME_SIZE
        if (createdAt < 0) return null

        val memberCount = readShort(bytes, offset) ?: return null
        offset += COUNT_SIZE
        // The bound first, the space the members would occupy second, and only then the allocation:
        // a count that does not fit in the rest of the record is refused without being trusted.
        if (memberCount > VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM) return null
        if (offset + memberCount * ID_SIZE > bytes.size) return null

        val members = ArrayList<VaultItemId>(memberCount)
        val seen = HashSet<VaultItemId>(memberCount)
        repeat(memberCount) {
            val itemId = VaultItemId.fromBytes(bytes.copyOfRange(offset, offset + ID_SIZE)) ?: return null
            offset += ID_SIZE
            if (!seen.add(itemId)) return null
            members += itemId
        }
        return VaultAlbum(
            id = albumId,
            name = name.first,
            createdAtEpochMillis = createdAt,
            itemIds = members,
        ) to offset
    }

    /**
     * Reads a two-byte-length string, returning it and the offset after it.
     *
     * The bytes are decoded strictly: a sequence that is not valid UTF-8 makes the record invalid
     * rather than being replaced with a substitute character. Replacement would turn damaged bytes into
     * a *different valid name*, and a name is what a person uses to recognise their own file.
     */
    private fun readBoundedText(bytes: ByteArray, start: Int, maximum: Int): Pair<String, Int>? {
        val length = readShort(bytes, start) ?: return null
        if (length < 1 || length > maximum) return null
        val from = start + LENGTH_SIZE
        if (from + length > bytes.size) return null
        val text = decodeStrictUtf8(bytes, from, length) ?: return null
        return text to (from + length)
    }

    /** [length] bytes of [bytes] from [offset] as strict UTF-8, or `null` when they are not. */
    private fun decodeStrictUtf8(bytes: ByteArray, offset: Int, length: Int): String? {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (malformed: CharacterCodingException) {
            null
        }
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
        if (offset + LENGTH_SIZE > bytes.size) return null
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

    /** The first generation an album record can have. */
    const val FIRST_GENERATION: Long = 1L
}
