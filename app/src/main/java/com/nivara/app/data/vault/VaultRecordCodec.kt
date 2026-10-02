package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultIdentity

/**
 * The vault's metadata record, version 1.
 *
 * ```
 * vault.0.nvm (the committed record)
 * offset  0        4        5         6          7           15
 *         | magic  | version| reserved| generation (8) | encrypted payload |
 *
 * payload (plaintext, before it is sealed by the envelope)
 * offset  0        4        5        6        7        8          16        32        34
 *         | magic  | version| reserved| keyScheme| generation (8) | vaultId (16) | length (2) | wrapped key |
 * ```
 *
 * Three things are going on here, and each answers a question the stage has to be able to answer
 * without guessing:
 *
 * * **Division of labour.** The outer header is deliberately *not* secret and *not* encrypted: it is
 *   what lets Nivara tell "there is no vault here" from "there is a vault here that I cannot open",
 *   and it has to be readable even when the key material is gone. The magic identifies a Nivara
 *   vault, the version identifies the format, and the generation orders two records against each
 *   other.
 * * **The payload is sealed.** The vault identifier and the wrapped key are encrypted and
 *   authenticated by the existing `EncryptionService` under `EncryptionContext.VaultMetadata`, so the
 *   wrapped key blob is not merely opaque but unreadable and unforgeable in place.
 * * **The generation is checked twice.** It appears in the clear header *and* inside the sealed
 *   payload. A reader accepts a record only when both agree: editing the clear header to promote an
 *   older record makes that record invalid rather than authoritative, so the worst an attacker with
 *   write access can do is cause a fallback to another record Nivara also authenticated.
 *
 * The record is a *header plus an opaque envelope*. This codec never touches a key, a cipher or a
 * nonce: it assembles and parses bytes, and the cryptographic work belongs to the Stage 2 services.
 */
internal object VaultRecordCodec {

    /** "NVVM" — Nivara vault metadata. */
    val RECORD_MAGIC: ByteArray = "NVVM".toByteArray(Charsets.US_ASCII)

    /** "NVVP" — Nivara vault payload, so a payload cannot be mistaken for the record that holds it. */
    val PAYLOAD_MAGIC: ByteArray = "NVVP".toByteArray(Charsets.US_ASCII)

    /** The only format version this build writes. Anything else is refused, never reinterpreted. */
    const val VERSION: Int = 1

    /** The only key scheme this build writes: the vault key wrapped by an existing wrapper. */
    const val KEY_SCHEME_WRAPPED_CONTENT_KEY: Int = 1

    /** Bytes of randomness behind a vault identity, as stored in the payload. */
    const val IDENTITY_SIZE: Int = 16

    const val GENERATION_SIZE: Int = 8
    const val WRAPPED_KEY_LENGTH_SIZE: Int = 2

    /** Everything before the envelope: magic, version, reserved, generation. */
    const val HEADER_LENGTH: Int = 4 + 1 + 1 + GENERATION_SIZE

    /** Everything before the wrapped key inside the payload. */
    const val PAYLOAD_HEADER_LENGTH: Int =
        4 + 1 + 1 + 1 + GENERATION_SIZE + IDENTITY_SIZE + WRAPPED_KEY_LENGTH_SIZE

    /**
     * The largest wrapped key Nivara will accept.
     *
     * Both wrapping schemes the project has produce blobs of a few dozen bytes; the bound exists so a
     * damaged length field cannot make the reader allocate or believe something absurd.
     */
    const val MAXIMUM_WRAPPED_KEY_LENGTH: Int = 4096

    /** The largest record Nivara will read from storage, header and envelope included. */
    const val MAXIMUM_RECORD_LENGTH: Int = 64 * 1024

    /** The first generation a vault's metadata can have. */
    const val FIRST_GENERATION: Long = 1L

    /** A record's clear header, as read from storage. */
    data class Header(
        val version: Int,
        val generation: Long,
    )

    /** What a clear header says about a blob of bytes, before anything is decrypted. */
    sealed interface HeaderRead {

        /** The bytes begin with Nivara's marker and carry a header this build can read. */
        data class Present(val header: Header) : HeaderRead

        /**
         * The bytes do not begin with Nivara's marker.
         *
         * For the *record* this means the file is not a vault record — a foreign file, or a write
         * that never got as far as the magic — so its presence is not evidence of a vault. For the
         * *payload* it means the sealed content is not a payload this build wrote.
         */
        data object NotAVaultRecord : HeaderRead

        /**
         * The bytes begin with Nivara's marker and the header cannot be used: too short to hold one,
         * or written by a version this build does not know.
         *
         * The two are one case for the caller but not one fact, so the version is carried when it
         * could be read at all: `null` means the bytes stopped before the version byte.
         */
        data class Unreadable(val fileVersion: Int?) : HeaderRead
    }

    /** A payload as read from a decrypted record. */
    data class Payload(
        val generation: Long,
        val scheme: Int,
        val identity: VaultIdentity,
        val wrappedKey: ByteArray,
    ) {
        // A data class would compare the blob by reference, so two payloads carrying the same bytes
        // would be unequal. Equality here means "the same payload", and the tests rely on it.
        override fun equals(other: Any?): Boolean = other is Payload &&
            generation == other.generation &&
            scheme == other.scheme &&
            identity == other.identity &&
            wrappedKey.contentEquals(other.wrappedKey)

        override fun hashCode(): Int =
            ((generation.hashCode() * 31 + scheme) * 31 + identity.hashCode()) * 31 +
                wrappedKey.contentHashCode()
    }

    /** Reads the clear header of [bytes] without decrypting anything. */
    fun readHeader(bytes: ByteArray): HeaderRead {
        if (bytes.size < RECORD_MAGIC.size || !bytes.startsWith(RECORD_MAGIC)) {
            return HeaderRead.NotAVaultRecord
        }
        if (bytes.size < HEADER_LENGTH) {
            // The marker is there and the record is too short to be one: this is a Nivara file that
            // was damaged or truncated, which is exactly the case that must not read as "no vault".
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

    /** Whether [bytes] is large enough to be a complete record. */
    fun isCompleteRecord(bytes: ByteArray): Boolean = bytes.size >= HEADER_LENGTH

    /**
     * Assembles a record around [envelope].
     *
     * The envelope must already exist: this function cannot create one, which is what keeps the
     * cryptographic work in the services that own it.
     */
    fun encodeRecord(generation: Long, envelope: ByteArray): ByteArray {
        require(generation >= FIRST_GENERATION) { "a generation starts at $FIRST_GENERATION" }
        require(envelope.isNotEmpty()) { "a record holds an envelope" }
        val buffer = ByteArray(HEADER_LENGTH + envelope.size)
        RECORD_MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        writeLong(buffer, 6, generation)
        envelope.copyInto(buffer, HEADER_LENGTH)
        return buffer
    }

    /** The envelope inside a record whose header has already been read. */
    fun envelopeOf(bytes: ByteArray): ByteArray = bytes.copyOfRange(HEADER_LENGTH, bytes.size)

    /** Assembles the payload that will be sealed into the envelope. */
    fun encodePayload(
        generation: Long,
        identity: VaultIdentity,
        wrappedKey: ByteArray,
    ): ByteArray {
        require(generation >= FIRST_GENERATION) { "a generation starts at $FIRST_GENERATION" }
        require(wrappedKey.size in 1..MAXIMUM_WRAPPED_KEY_LENGTH) {
            "a wrapped key is between 1 and $MAXIMUM_WRAPPED_KEY_LENGTH bytes"
        }
        val identityBytes = identity.toBytes() ?: throw IllegalArgumentException("identity is malformed")
        val buffer = ByteArray(PAYLOAD_HEADER_LENGTH + wrappedKey.size)
        PAYLOAD_MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        buffer[6] = KEY_SCHEME_WRAPPED_CONTENT_KEY.toByte()
        writeLong(buffer, 7, generation)
        identityBytes.copyInto(buffer, 7 + GENERATION_SIZE)
        val lengthOffset = 7 + GENERATION_SIZE + IDENTITY_SIZE
        buffer[lengthOffset] = (wrappedKey.size ushr 8).toByte()
        buffer[lengthOffset + 1] = wrappedKey.size.toByte()
        wrappedKey.copyInto(buffer, PAYLOAD_HEADER_LENGTH)
        return buffer
    }

    /**
     * Parses a decrypted payload, or returns `null` when it is not one this build wrote.
     *
     * `null` is not "empty": every structural rule is checked (magic, version, reserved byte, scheme,
     * identity length, declared length, trailing bytes) and any violation means the record is not
     * trustworthy rather than that the vault holds nothing.
     */
    fun decodePayload(bytes: ByteArray): Payload? {
        if (bytes.size < PAYLOAD_HEADER_LENGTH || !bytes.startsWith(PAYLOAD_MAGIC)) return null
        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) return null
        if ((bytes[5].toInt() and 0xFF) != 0) return null
        val scheme = bytes[6].toInt() and 0xFF
        if (scheme != KEY_SCHEME_WRAPPED_CONTENT_KEY) return null
        val generation = readLong(bytes, 7)
        if (generation < FIRST_GENERATION) return null
        val identityOffset = 7 + GENERATION_SIZE
        val identity = VaultIdentity.fromBytes(
            bytes.copyOfRange(identityOffset, identityOffset + IDENTITY_SIZE),
        ) ?: return null
        val lengthOffset = identityOffset + IDENTITY_SIZE
        val declaredLength = ((bytes[lengthOffset].toInt() and 0xFF) shl 8) or
            (bytes[lengthOffset + 1].toInt() and 0xFF)
        if (declaredLength !in 1..MAXIMUM_WRAPPED_KEY_LENGTH) return null
        // Nothing may follow the wrapped key: trailing bytes would mean the payload is not the one
        // this codec wrote, and accepting them would make the format ambiguous.
        if (bytes.size != PAYLOAD_HEADER_LENGTH + declaredLength) return null
        return Payload(
            generation = generation,
            scheme = scheme,
            identity = identity,
            wrappedKey = bytes.copyOfRange(PAYLOAD_HEADER_LENGTH, bytes.size),
        )
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
