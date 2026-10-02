package com.nivara.app.data.credential

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.credential.CredentialFailure
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32

/**
 * Storage helpers shared by the credential record and the attempt counters.
 *
 * Both files are small, written whole and replaced atomically. They live in the application's
 * private directory, which on Android is readable only by the application's own user id, so
 * there is nothing to encrypt for another app to be kept out of — and, importantly, the
 * credential record contains nothing worth encrypting (see [StoredCredential]).
 */
internal object AtomicFiles {

    /** A failed replacement left this file behind; it is never read. */
    private const val TEMPORARY_SUFFIX = ".tmp"

    /** The contents of [file], or `null` when it does not exist. */
    fun readOrNull(file: File): ByteArray? = if (file.exists()) file.readBytes() else null

    /**
     * Writes [bytes] to [file] through a temporary file and an atomic rename.
     *
     * The rename is what makes the write atomic: a reader either sees the previous record in
     * full or the new one in full, never a half-written file. The temporary file is flushed and
     * synced first so the rename cannot publish data that has not reached storage.
     */
    fun write(file: File, bytes: ByteArray) {
        val directory = file.parentFile
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw IOException("could not create the credential directory")
        }

        val temporary = File(directory, file.name + TEMPORARY_SUFFIX)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                throw IOException("could not replace the credential file")
            }
        } finally {
            // On success the rename already removed it; on failure it must not survive.
            if (temporary.exists()) temporary.delete()
        }
    }
}

/**
 * Maps storage trouble onto the typed failure callers expect.
 *
 * A missing file, a permission problem or a corrupt record all reach the caller as a
 * [CredentialFailure], never as a platform exception with a path or a stack trace in it.
 */
internal fun <T> NivaraResult<T>.asStorageFailure(): NivaraResult<T> =
    if (this is NivaraResult.Failure) {
        if (error is CredentialFailure) this else NivaraResult.Failure(CredentialFailure.StorageUnavailable)
    } else {
        this
    }

/** Writes a 32-bit big-endian integer. */
internal fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = (value shr 24).toByte()
    buffer[offset + 1] = (value shr 16).toByte()
    buffer[offset + 2] = (value shr 8).toByte()
    buffer[offset + 3] = value.toByte()
}

/** Reads a 32-bit big-endian integer. */
internal fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

/** Writes a 64-bit big-endian integer. */
internal fun writeLong(buffer: ByteArray, offset: Int, value: Long) {
    for (index in 0 until LONG_BYTES) {
        buffer[offset + index] = (value shr ((LONG_BYTES - 1 - index) * BITS_PER_BYTE)).toByte()
    }
}

/** Reads a 64-bit big-endian integer. */
internal fun readLong(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 0 until LONG_BYTES) {
        value = (value shl BITS_PER_BYTE) or (bytes[offset + index].toLong() and 0xFFL)
    }
    return value
}

/**
 * CRC-32 over `bytes[from, to)`.
 *
 * This detects accidental corruption, and nothing else. It is not a message authentication code:
 * anyone who can rewrite the file can rewrite the checksum too. That is deliberate — the record
 * holds no secret, and an attacker who can write to the application's private directory is
 * already outside the threat model Nivara defends against.
 */
internal fun checksum(bytes: ByteArray, from: Int, to: Int): Int {
    val crc = CRC32()
    crc.update(bytes, from, to - from)
    return crc.value.toInt()
}

private const val LONG_BYTES = 8
private const val BITS_PER_BYTE = 8
