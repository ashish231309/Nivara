package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultContentDigest
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultItemNames

/**
 * The vault's content index, version 1.
 *
 * ```
 * index.0.nvi
 * offset  0        4        5         6               14
 *         | "NVIN" | version| reserved | generation (8) | sealed payload |
 *
 * payload (plaintext, before the envelope seals it)
 * offset  0        4        5         6              14           22          24
 *         | "NVIN" | version| reserved | generation (8)| vault generation (8)| item count (2) | items… |
 *
 * item
 * offset  0         16        18            18 + name | +2 | mime | 8 | 8 | 1 | 32
 *         | id (16) | name length (2)| name (utf-8) | mime length (2) | mime (utf-8) |
 *         | size (8) | imported at (8) | content format (1) | content digest (32) |
 * ```
 *
 * ### The same shape as the vault's own record, for the same reasons
 *
 * * **The clear header is not secret and not encrypted.** It says this is a Nivara index, which
 *   format version wrote it, and which generation it is, so a reader can tell "no index yet" from
 *   "an index I cannot open" and from "an index a newer Nivara wrote" without holding a key.
 * * **The payload is sealed by the existing cryptographic layer** under
 *   [`EncryptionContext.VaultIndex`][com.nivara.app.domain.security.EncryptionContext.VaultIndex] —
 *   a purpose of its own, so an index envelope can never be accepted where the vault's own record is
 *   expected, or the other way round.
 * * **The generation is checked twice**, in the clear header and inside the sealed payload. Editing
 *   the header to promote an older index therefore makes that index invalid rather than
 *   authoritative.
 * * **Parsing is bounded and strict.** Every length is checked against a bound before it is used,
 *   every string is validated as a name or a type, ids must be exactly sixteen bytes of hex, a
 *   duplicate id makes the whole record invalid, and trailing bytes are refused rather than ignored.
 *   `null` never means "empty": an index with no items is a valid record with a count of zero.
 *
 * This codec holds no key and touches no cipher: it assembles and parses bytes, and the sealing
 * belongs to the encryption service.
 */
internal object VaultIndexCodec {

    /** "NVIN" — Nivara vault index. */
    val MAGIC: ByteArray = "NVIN".toByteArray(Charsets.US_ASCII)

    /** The only format version this build writes. Anything else is refused, never reinterpreted. */
    const val VERSION: Int = 1

    const val ID_SIZE: Int = 16
    const val DIGEST_SIZE: Int = 32
    const val GENERATION_SIZE: Int = 8
    const val COUNT_SIZE: Int = 2
    const val LENGTH_SIZE: Int = 2
    const val SIZE_SIZE: Int = 8
    const val TIME_SIZE: Int = 8
    const val CONTENT_FORMAT_SIZE: Int = 1

    /** The clear header of an index record: magic, version, reserved, generation. */
    const val HEADER_LENGTH: Int = 4 + 1 + 1 + GENERATION_SIZE

    /** The payload's own header, before the items. */
    const val PAYLOAD_HEADER_LENGTH: Int = MAGIC.size + 1 + 1 + GENERATION_SIZE + GENERATION_SIZE + COUNT_SIZE

    /** The most items one index record can name, fixed by the two-byte count. */
    const val MAXIMUM_ITEM_COUNT: Int = 65_535

    /** Bounds for the text an item may carry, in encoded bytes. */
    const val MAXIMUM_NAME_BYTES: Int = VaultItemNames.MAXIMUM_LENGTH * 4
    const val MAXIMUM_MIME_BYTES: Int = VaultItemNames.MAXIMUM_MIME_LENGTH

    /** The largest index record Nivara will read, sealing included. */
    const val MAXIMUM_INDEX_LENGTH: Int = 4 * 1024 * 1024

    /** What a clear header says about a blob of bytes, before anything is decrypted. */
    sealed interface HeaderRead {

        /** The bytes begin with Nivara's marker and carry a header this build can read. */
        data class Present(val generation: Long) : HeaderRead

        /** The bytes do not begin with Nivara's marker, so they are not an index record at all. */
        data object NotAVaultIndex : HeaderRead

        /**
         * The bytes begin with Nivara's marker and the header cannot be used: too short to hold one,
         * a set reserved byte, or a version this build does not know. The version is carried when it
         * could be read, so an index from a newer Nivara is reported as such rather than as damage.
         */
        data class Unreadable(val fileVersion: Int?) : HeaderRead
    }

    /** A payload as read from a decrypted index record. */
    data class Payload(
        val generation: Long,
        val vaultGeneration: Long,
        val items: List<VaultItem>,
    )

    /** Reads the clear header of [bytes] without decrypting anything. */
    fun readHeader(bytes: ByteArray): HeaderRead {
        if (bytes.size < MAGIC.size || !bytes.startsWith(MAGIC)) return HeaderRead.NotAVaultIndex
        if (bytes.size < HEADER_LENGTH) {
            // The marker is there and the record is too short to be one: a Nivara file that was
            // damaged, which is exactly the case that must not read as "no index yet".
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
     * The plaintext the envelope is to seal for [items], or `null` when they cannot be encoded.
     *
     * Returning `null` rather than throwing keeps the "this cannot be written" decision at the call
     * site, where the typed failure is, and keeps this function free of policy.
     */
    fun encodePayload(
        generation: Long,
        vaultGeneration: Long,
        items: List<VaultItem>,
    ): ByteArray? {
        if (generation < FIRST_GENERATION) return null
        if (items.size > MAXIMUM_ITEM_COUNT) return null

        val encodedItems = mutableListOf<ByteArray>()
        var total = PAYLOAD_HEADER_LENGTH
        for (item in items) {
            val encoded = encodeItem(item) ?: return null
            total += encoded.size
            if (total > MAXIMUM_INDEX_LENGTH) return null
            encodedItems += encoded
        }

        val buffer = ByteArray(total)
        MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        writeLong(buffer, 6, generation)
        writeLong(buffer, 14, vaultGeneration)
        buffer[22] = (items.size ushr 8).toByte()
        buffer[23] = items.size.toByte()
        var offset = PAYLOAD_HEADER_LENGTH
        for (encoded in encodedItems) {
            encoded.copyInto(buffer, offset)
            offset += encoded.size
        }
        return buffer
    }

    /**
     * Parses a decrypted payload, or returns `null` when it is not one this build wrote.
     *
     * `null` is not "empty": every structural rule is checked — magic, version, reserved byte,
     * generation, count, every length, every name, every type, every id, duplicates and trailing
     * bytes — and any violation means the record is not trustworthy rather than that the vault holds
     * nothing.
     *
     * @param expectedGeneration the generation the clear header carried. A payload that disagrees
     *   with its own header belongs to a record somebody edited, and is refused.
     */
    fun decodePayload(bytes: ByteArray, expectedGeneration: Long): Payload? {
        if (bytes.size < PAYLOAD_HEADER_LENGTH || !bytes.startsWith(MAGIC)) return null
        if ((bytes[4].toInt() and 0xFF) != VERSION) return null
        if ((bytes[5].toInt() and 0xFF) != 0) return null
        val generation = readLong(bytes, 6)
        if (generation != expectedGeneration) return null
        if (generation < FIRST_GENERATION) return null
        val vaultGeneration = readLong(bytes, 14)
        if (vaultGeneration < FIRST_GENERATION) return null
        val count = ((bytes[22].toInt() and 0xFF) shl 8) or (bytes[23].toInt() and 0xFF)

        val items = ArrayList<VaultItem>(count)
        val seen = HashSet<VaultItemId>(count)
        var offset = PAYLOAD_HEADER_LENGTH
        repeat(count) {
            val decoded = decodeItem(bytes, offset) ?: return null
            val item = decoded.first
            offset = decoded.second
            if (!seen.add(item.id)) {
                // The same id twice: one of the two records describes an object the other may also
                // describe, and neither can be trusted to say which.
                return null
            }
            items += item
        }
        // Nothing may follow the last item: trailing bytes would mean the payload is not the one this
        // codec wrote, and accepting them would make the format ambiguous.
        if (offset != bytes.size) return null
        return Payload(generation = generation, vaultGeneration = vaultGeneration, items = items)
    }

    /** One encoded item: id, name, type, size, time, content format and digest. */
    private fun encodeItem(item: VaultItem): ByteArray? {
        val idBytes = item.id.toBytes()
        val nameBytes = item.name.toByteArray(Charsets.UTF_8)
        val mimeBytes = item.mimeType?.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAXIMUM_NAME_BYTES) return null
        if (mimeBytes != null && mimeBytes.size > MAXIMUM_MIME_BYTES) return null
        if (!VaultItemNames.isWellFormed(item.name)) return null
        if (item.mimeType != null && !VaultItemNames.isWellFormedMimeType(item.mimeType)) return null
        if (item.sizeBytes < 0 || item.importedAtEpochMillis < 0) return null
        if (item.contentFormatVersion < 1) return null
        val digestBytes = item.contentDigest.toBytes() ?: return null

        val size = ID_SIZE + LENGTH_SIZE + nameBytes.size + LENGTH_SIZE +
            (mimeBytes?.size ?: 0) + SIZE_SIZE + TIME_SIZE + CONTENT_FORMAT_SIZE + DIGEST_SIZE
        val buffer = ByteArray(size)
        var offset = 0
        idBytes.copyInto(buffer, offset)
        offset += ID_SIZE
        writeShort(buffer, offset, nameBytes.size)
        offset += LENGTH_SIZE
        nameBytes.copyInto(buffer, offset)
        offset += nameBytes.size
        writeShort(buffer, offset, mimeBytes?.size ?: NONE)
        offset += LENGTH_SIZE
        if (mimeBytes != null) {
            mimeBytes.copyInto(buffer, offset)
            offset += mimeBytes.size
        }
        writeLong(buffer, offset, item.sizeBytes)
        offset += SIZE_SIZE
        writeLong(buffer, offset, item.importedAtEpochMillis)
        offset += TIME_SIZE
        buffer[offset] = item.contentFormatVersion.toByte()
        offset += CONTENT_FORMAT_SIZE
        digestBytes.copyInto(buffer, offset)
        return buffer
    }

    /** One decoded item and the offset after it. */
    private fun decodeItem(bytes: ByteArray, start: Int): Pair<VaultItem, Int>? {
        var offset = start
        if (offset + ID_SIZE > bytes.size) return null
        val id = VaultItemId.fromBytes(bytes.copyOfRange(offset, offset + ID_SIZE)) ?: return null
        offset += ID_SIZE

        val name = readBoundedText(bytes, offset, MAXIMUM_NAME_BYTES) ?: return null
        offset = name.second
        if (!VaultItemNames.isWellFormed(name.first)) return null

        val mimeLength = readShort(bytes, offset) ?: return null
        offset += LENGTH_SIZE
        val mimeType = when {
            mimeLength == NONE -> null
            mimeLength in 1..MAXIMUM_MIME_BYTES -> {
                if (offset + mimeLength > bytes.size) return null
                val text = String(bytes, offset, mimeLength, Charsets.UTF_8)
                offset += mimeLength
                if (!VaultItemNames.isWellFormedMimeType(text)) return null
                text
            }
            else -> return null
        }

        if (offset + SIZE_SIZE + TIME_SIZE + CONTENT_FORMAT_SIZE + DIGEST_SIZE > bytes.size) return null
        val sizeBytes = readLong(bytes, offset)
        offset += SIZE_SIZE
        val importedAt = readLong(bytes, offset)
        offset += TIME_SIZE
        val contentFormat = bytes[offset].toInt() and 0xFF
        offset += CONTENT_FORMAT_SIZE
        val digest = VaultContentDigest.fromBytes(bytes.copyOfRange(offset, offset + DIGEST_SIZE))
            ?: return null
        offset += DIGEST_SIZE

        if (sizeBytes < 0 || importedAt < 0 || contentFormat < 1) return null
        return VaultItem(
            id = id,
            name = name.first,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            importedAtEpochMillis = importedAt,
            contentFormatVersion = contentFormat,
            contentDigest = digest,
        ) to offset
    }

    /** Reads a two-byte-length string, returning it and the offset after it. */
    private fun readBoundedText(bytes: ByteArray, start: Int, maximum: Int): Pair<String, Int>? {
        val length = readShort(bytes, start) ?: return null
        if (length < 1 || length > maximum) return null
        val from = start + LENGTH_SIZE
        if (from + length > bytes.size) return null
        return String(bytes, from, length, Charsets.UTF_8) to (from + length)
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

    /** The first generation an index record can have. */
    const val FIRST_GENERATION: Long = 1L

    /** Sentinel for "this item has no declared type". */
    private const val NONE = 0

    private fun VaultContentDigest.toBytes(): ByteArray? {
        if (!VaultContentDigest.isWellFormed(value)) return null
        val bytes = ByteArray(DIGEST_SIZE)
        for (index in bytes.indices) {
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }
}
