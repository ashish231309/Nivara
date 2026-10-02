package com.nivara.app.data.security

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * SHA-256 computed while data passes through, so a value's digest can be checked without ever
 * holding the value.
 *
 * The vault records the digest of each imported encrypted object in its index, and it computes that
 * digest from the bytes it reads *back* out of storage — not from the bytes it intended to write.
 * That is the whole point: the digest describes what is actually there, so checking an object later
 * is a comparison of two facts about storage rather than a memory of what a write call was asked to
 * do.
 *
 * A digest is not a key and not a secret. It authenticates nothing on its own — an attacker who can
 * rewrite an object can rewrite its digest — which is why the index that carries it is itself
 * authenticated, and why the object's own ciphertext carries its own tags.
 */
internal object ContentDigester {

    /** The digest algorithm. One algorithm, named once. */
    const val ALGORITHM: String = "SHA-256"

    /** Bytes of [ALGORITHM] output. */
    const val DIGEST_BYTES: Int = 32

    /** A sink that digests and counts everything written through it. */
    class DigestingOutputStream(private val delegate: OutputStream) : OutputStream() {

        private val digest = MessageDigest.getInstance(ALGORITHM)

        /** Bytes written so far. */
        var bytesWritten: Long = 0L
            private set

        override fun write(byte: Int) {
            digest.update(byte.toByte())
            delegate.write(byte)
            bytesWritten += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            digest.update(buffer, offset, length)
            delegate.write(buffer, offset, length)
            bytesWritten += length
        }

        override fun flush() {
            delegate.flush()
        }

        /** The digest of everything written through this sink. */
        fun digest(): ByteArray = digest.digest()
    }

    /** A source that digests and counts everything read through it. */
    class DigestingInputStream(private val delegate: InputStream) : InputStream() {

        private val digest = MessageDigest.getInstance(ALGORITHM)

        /** Bytes read so far. */
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val byte = delegate.read()
            if (byte >= 0) {
                digest.update(byte.toByte())
                bytesRead += 1
            }
            return byte
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) {
                digest.update(buffer, offset, count)
                bytesRead += count
            }
            return count
        }

        /** The digest of everything read through this source. */
        fun digest(): ByteArray = digest.digest()
    }

    /** A sink that counts what it is given and keeps nothing. */
    class CountingOutputStream : OutputStream() {

        /** Bytes written so far. */
        var bytesWritten: Long = 0L
            private set

        override fun write(byte: Int) {
            bytesWritten += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytesWritten += length
        }
    }
}
