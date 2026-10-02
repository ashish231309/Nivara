package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultIdentity

/**
 * The vault's recovery record, version 1.
 *
 * ```
 * recovery.0.nvr (the committed record)
 * offset  0        4        5         6          14
 *         | magic  | version| reserved| generation (8) | payload |
 *
 * payload (cleartext — it carries no secret)
 * offset  0        4        5         6          14         30        62        64
 *         | magic  | version| reserved| generation (8) | identity (16) | proof (32) | length (2) | envelope |
 * ```
 *
 * The record exists so a fresh installation — one that holds no device key and no stored location —
 * can find its way back into a vault that outlived it. That fixes what may and may not be in it:
 *
 * * **Readable without any key.** After a reinstall the header *and* the payload must be parseable,
 *   because parsing them is what lets recovery recognize the vault before it asks for anything. The
 *   record therefore never contains a secret: the envelope inside it is itself sealed, by the Stage
 *   2 recovery envelope service, under the user's recovery key.
 * * **The identity is in the clear on purpose.** It is the vault's non-secret label, shown as a
 *   fingerprint so the user can confirm which vault they selected before spending recovery material
 *   on it. Identity alone opens nothing.
 * * **The proof binds the key to this vault.** It is an HMAC over the identity under the vault key
 *   itself. The envelope says "this recovery key opens something"; the proof says "what it opens is
 *   the key of *this* vault". Anyone able to recompute the proof holds the vault key, and anyone
 *   without it cannot substitute another vault's envelope without the check below failing.
 * * **The generation is checked twice**, exactly like the vault's own record: it stands in the clear
 *   header and again inside the payload, and a reader accepts the record only when both agree.
 *
 * Like every codec in the vault, this one assembles and parses bytes only. The sealing of the
 * envelope and the computation of the proof belong to the services that own keys.
 */
internal object VaultRecoveryCodec {

    /** "NVRC" — Nivara vault recovery record. */
    val RECORD_MAGIC: ByteArray = "NVRC".toByteArray(Charsets.US_ASCII)

    /** "NVRP" — Nivara vault recovery payload, so a payload cannot be mistaken for its record. */
    val PAYLOAD_MAGIC: ByteArray = "NVRP".toByteArray(Charsets.US_ASCII)

    /** The only format version this build writes. Anything else is refused, never reinterpreted. */
    const val VERSION: Int = 1

    const val IDENTITY_SIZE: Int = 16
    const val PROOF_SIZE: Int = 32
    const val GENERATION_SIZE: Int = 8
    const val ENVELOPE_LENGTH_SIZE: Int = 2

    /** Everything before the payload: magic, version, reserved, generation. */
    const val HEADER_LENGTH: Int = 4 + 1 + 1 + GENERATION_SIZE

    /** Everything before the envelope inside the payload. */
    const val PAYLOAD_HEADER_LENGTH: Int =
        4 + 1 + 1 + GENERATION_SIZE + IDENTITY_SIZE + PROOF_SIZE + ENVELOPE_LENGTH_SIZE

    /**
     * The largest envelope Nivara will accept.
     *
     * A recovery envelope around a 256-bit key is a few dozen bytes; the bound exists so a damaged
     * length field cannot make the reader allocate or believe something absurd.
     */
    const val MAXIMUM_ENVELOPE_LENGTH: Int = 4096

    /** The largest recovery record Nivara will read from storage, header and payload included. */
    const val MAXIMUM_RECORD_LENGTH: Int = 64 * 1024

    /** The first generation a recovery record can have. */
    const val FIRST_GENERATION: Long = 1L

    /** The domain the proof is computed in: HMAC input prefix binding the proof to this purpose. */
    val PROOF_INFO: ByteArray = "NivaraRecoveryProof/1".toByteArray(Charsets.US_ASCII)

    /** A recovery record's clear header, as read from storage. */
    data class Header(
        val version: Int,
        val generation: Long,
    )

    /** What a clear header says about a blob of bytes, before anything is parsed further. */
    sealed interface HeaderRead {

        /** The bytes begin with Nivara's recovery marker and carry a header this build can read. */
        data class Present(val header: Header) : HeaderRead

        /** The bytes do not begin with the recovery marker: not a recovery record. */
        data object NotARecoveryRecord : HeaderRead

        /**
         * The bytes begin with the marker and the header cannot be used: too short to hold one, a
         * reserved byte in use, or written by a version this build does not know.
         */
        data class Unreadable(val fileVersion: Int?) : HeaderRead
    }

    /** A recovery record's payload, as read from storage. */
    data class Payload(
        val generation: Long,
        val identity: VaultIdentity,
        val proof: ByteArray,
        val envelope: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Payload &&
            generation == other.generation &&
            identity == other.identity &&
            proof.contentEquals(other.proof) &&
            envelope.contentEquals(other.envelope)

        override fun hashCode(): Int =
            (((generation.hashCode() * 31 + identity.hashCode()) * 31 +
                proof.contentHashCode()) * 31) + envelope.contentHashCode()
    }

    /** Reads the clear header of [bytes]. */
    fun readHeader(bytes: ByteArray): HeaderRead {
        if (bytes.size < RECORD_MAGIC.size || !bytes.startsWith(RECORD_MAGIC)) {
            return HeaderRead.NotARecoveryRecord
        }
        if (bytes.size < HEADER_LENGTH) {
            // The marker is there and the record is too short to be one: a recovery file that was
            // damaged or truncated, which must not read as "no recovery record".
            return HeaderRead.Unreadable(fileVersion = bytes.getOrNull(RECORD_MAGIC.size)?.toInt()?.and(0xFF))
        }
        val version = bytes[RECORD_MAGIC.size].toInt() and 0xFF
        val reserved = bytes[RECORD_MAGIC.size + 1].toInt() and 0xFF
        if (reserved != 0) return HeaderRead.Unreadable(fileVersion = version)
        if (version != VERSION) return HeaderRead.Unreadable(fileVersion = version)
        val generation = readLong(bytes, RECORD_MAGIC.size + 2)
        if (generation < FIRST_GENERATION) return HeaderRead.Unreadable(fileVersion = version)
        return HeaderRead.Present(Header(version = version, generation = generation))
    }

    /** Assembles a record around [payload]. */
    fun encodeRecord(generation: Long, payload: ByteArray): ByteArray {
        require(generation >= FIRST_GENERATION) { "a generation starts at $FIRST_GENERATION" }
        require(payload.isNotEmpty()) { "a record holds a payload" }
        val buffer = ByteArray(HEADER_LENGTH + payload.size)
        RECORD_MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        writeLong(buffer, 6, generation)
        payload.copyInto(buffer, HEADER_LENGTH)
        return buffer
    }

    /** The payload inside a record whose header has already been read. */
    fun payloadOf(bytes: ByteArray): ByteArray = bytes.copyOfRange(HEADER_LENGTH, bytes.size)

    /** Assembles a payload around a sealed [envelope]. */
    fun encodePayload(
        generation: Long,
        identity: VaultIdentity,
        proof: ByteArray,
        envelope: ByteArray,
    ): ByteArray {
        require(generation >= FIRST_GENERATION) { "a generation starts at $FIRST_GENERATION" }
        require(proof.size == PROOF_SIZE) { "a proof is $PROOF_SIZE bytes" }
        require(envelope.size in 1..MAXIMUM_ENVELOPE_LENGTH) {
            "an envelope is between 1 and $MAXIMUM_ENVELOPE_LENGTH bytes"
        }
        val identityBytes = identity.toBytes() ?: throw IllegalArgumentException("identity is malformed")
        val buffer = ByteArray(PAYLOAD_HEADER_LENGTH + envelope.size)
        PAYLOAD_MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        // The generation is committed inside the payload too: a reader accepts a record only when
        // header and payload agree about it, exactly like the vault's own record.
        writeLong(buffer, 6, generation)
        identityBytes.copyInto(buffer, 6 + GENERATION_SIZE)
        proof.copyInto(buffer, 6 + GENERATION_SIZE + IDENTITY_SIZE)
        val lengthOffset = 6 + GENERATION_SIZE + IDENTITY_SIZE + PROOF_SIZE
        buffer[lengthOffset] = (envelope.size ushr 8).toByte()
        buffer[lengthOffset + 1] = envelope.size.toByte()
        envelope.copyInto(buffer, PAYLOAD_HEADER_LENGTH)
        return buffer
    }

    /**
     * Parses a payload, or returns `null` when it is not one this build wrote.
     *
     * `null` is not "empty": every structural rule is checked (magic, version, reserved byte,
     * identity shape, declared length, trailing bytes) and any violation means the record is not
     * trustworthy rather than that recovery is merely absent.
     */
    fun decodePayload(bytes: ByteArray, expectedGeneration: Long): Payload? {
        if (bytes.size < PAYLOAD_HEADER_LENGTH || !bytes.startsWith(PAYLOAD_MAGIC)) return null
        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) return null
        if ((bytes[5].toInt() and 0xFF) != 0) return null
        val generation = readLong(bytes, 6)
        if (generation != expectedGeneration) return null
        val identityOffset = 6 + GENERATION_SIZE
        val identity = VaultIdentity.fromBytes(
            bytes.copyOfRange(identityOffset, identityOffset + IDENTITY_SIZE),
        ) ?: return null
        val proof = bytes.copyOfRange(
            identityOffset + IDENTITY_SIZE,
            identityOffset + IDENTITY_SIZE + PROOF_SIZE,
        )
        val lengthOffset = identityOffset + IDENTITY_SIZE + PROOF_SIZE
        val declaredLength = ((bytes[lengthOffset].toInt() and 0xFF) shl 8) or
            (bytes[lengthOffset + 1].toInt() and 0xFF)
        if (declaredLength !in 1..MAXIMUM_ENVELOPE_LENGTH) return null
        // Nothing may follow the envelope: trailing bytes would mean the payload is not the one this
        // codec wrote, and accepting them would make the format ambiguous.
        if (bytes.size != PAYLOAD_HEADER_LENGTH + declaredLength) return null
        val envelope = bytes.copyOfRange(PAYLOAD_HEADER_LENGTH, bytes.size)
        val payload = Payload(
            generation = expectedGeneration,
            identity = identity,
            proof = proof,
            envelope = envelope,
        )
        return payload
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private fun VaultIdentity.toBytes(): ByteArray? {
        if (!VaultIdentity.isWellFormed(value)) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun writeLong(buffer: ByteArray, offset: Int, value: Long) {
        for (index in 0 until GENERATION_SIZE) {
            buffer[offset + index] = (value shr ((GENERATION_SIZE - 1 - index) * 8)).toByte()
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until GENERATION_SIZE) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        return value
    }
}
