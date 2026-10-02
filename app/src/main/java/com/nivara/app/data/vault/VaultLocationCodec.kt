package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultLocation

/**
 * The record of which folder holds the vault, version 1.
 *
 * ```
 * vault-location.nvl
 * offset  0        4        5         6            10
 *         | "NVLP" | version| reserved | length (4) | utf-8 reference |
 * ```
 *
 * It holds one thing and nothing else: the platform's own reference for the folder the user selected.
 * No key material, no credential, no copy of the vault's metadata and no vault identity — everything
 * else lives inside the vault, where the vault's own authenticated record protects it. A second copy
 * of anything here would be a second thing that can disagree.
 *
 * The reference itself is not a secret. It names a folder its owner can usually see in their own file
 * manager, and what protects the vault is the encryption, not the location. Nivara still keeps it out
 * of every message, log and screen: a platform URI is long, unreadable and meaningless to a person.
 *
 * Versioning is deliberate and small. A record this build does not understand is reported as
 * unreadable rather than reinterpreted, and the reader never repairs, rewrites or deletes it — the
 * bytes are the only remaining hint of where the vault is, so a damaged record must not become "no
 * vault is configured".
 */
internal object VaultLocationCodec {

    /** "NVLP" — Nivara vault location. */
    val MAGIC: ByteArray = "NVLP".toByteArray(Charsets.US_ASCII)

    /** The only version this build writes. */
    const val VERSION: Int = 1

    /** Magic, version, reserved byte and the 32-bit reference length. */
    const val HEADER_LENGTH: Int = 4 + 1 + 1 + 4

    /** Encodes [location] as the bytes of the record. */
    fun encode(location: VaultLocation): ByteArray {
        val referenceBytes = location.reference.toByteArray(Charsets.UTF_8)
        require(referenceBytes.size <= VaultLocation.MAXIMUM_REFERENCE_LENGTH) {
            "a vault location reference is bounded"
        }
        val buffer = ByteArray(HEADER_LENGTH + referenceBytes.size)
        MAGIC.copyInto(buffer, 0)
        buffer[4] = VERSION.toByte()
        buffer[5] = 0
        writeInt(buffer, 6, referenceBytes.size)
        referenceBytes.copyInto(buffer, HEADER_LENGTH)
        return buffer
    }

    /**
     * The location in [bytes], or `null` when the record is not one this build wrote.
     *
     * `null` covers every way a record can be wrong — a foreign magic, an unknown version, a reserved
     * byte that is not zero, a length that does not match the bytes, an empty or blank reference — and
     * the caller turns all of them into the same honest answer: this record cannot be read.
     */
    fun decode(bytes: ByteArray): VaultLocation? {
        if (bytes.size < HEADER_LENGTH) return null
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return null
        }
        if ((bytes[4].toInt() and 0xFF) != VERSION) return null
        if ((bytes[5].toInt() and 0xFF) != 0) return null
        val length = readInt(bytes, 6)
        if (length !in 1..VaultLocation.MAXIMUM_REFERENCE_LENGTH) return null
        if (bytes.size != HEADER_LENGTH + length) return null
        val reference = String(bytes, HEADER_LENGTH, length, Charsets.UTF_8)
        if (reference.isBlank()) return null
        return VaultLocation(reference)
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value shr 24).toByte()
        buffer[offset + 1] = (value shr 16).toByte()
        buffer[offset + 2] = (value shr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
