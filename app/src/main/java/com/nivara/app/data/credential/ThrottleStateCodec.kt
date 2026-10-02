package com.nivara.app.data.credential

import com.nivara.app.domain.credential.ThrottleState

/**
 * The on-disk form of the attempt counters.
 *
 * ```
 *  offset  size  field
 *  ------  ----  ------------------------------------------------
 *       0     4  magic "NVAT"
 *       4     1  format version (1)
 *       5     4  consecutive failures, big-endian
 *       9     8  block end, milliseconds since the epoch, big-endian
 *      17     4  CRC-32 of the preceding bytes, big-endian
 * ```
 *
 * Twenty-one bytes, holding two numbers and nothing else. There is no per-attempt history, no
 * record of when a credential was last changed and nothing that describes the user's behaviour
 * beyond what the throttling rule needs to work.
 *
 * The file is written on every rejected attempt and after every successful one. If it is
 * unreadable, the tracker starts from a clean state instead of failing: a rate limiter that can
 * lock a user out of their own device because a counter file was damaged is worse than the
 * guessing it prevents. That is why nothing in the design treats this file as authoritative.
 */
internal object ThrottleStateCodec {

    /** Version of the counter format written by this build. */
    const val FORMAT_VERSION: Int = 1

    private const val MAGIC_BYTES = 4
    private const val VERSION_INDEX = MAGIC_BYTES
    private const val FAILURES_INDEX = VERSION_INDEX + 1
    private const val BLOCKED_UNTIL_INDEX = FAILURES_INDEX + 4
    private const val CHECKSUM_BYTES = 4

    /** Fixed size of the encoded form. */
    const val LENGTH_BYTES: Int = BLOCKED_UNTIL_INDEX + 8 + CHECKSUM_BYTES

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())

    /** Encodes [state] into its on-disk form. */
    fun encode(state: ThrottleState): ByteArray {
        val buffer = ByteArray(LENGTH_BYTES)
        MAGIC.copyInto(buffer, 0)
        buffer[VERSION_INDEX] = FORMAT_VERSION.toByte()
        writeInt(buffer, FAILURES_INDEX, state.consecutiveFailures)
        writeLong(buffer, BLOCKED_UNTIL_INDEX, state.blockedUntilMillis)
        writeInt(buffer, LENGTH_BYTES - CHECKSUM_BYTES, checksum(buffer, 0, LENGTH_BYTES - CHECKSUM_BYTES))
        return buffer
    }

    /** Decodes [bytes], or returns `null` when they are not a counter file this build wrote. */
    fun decode(bytes: ByteArray): ThrottleState? {
        if (bytes.size != LENGTH_BYTES) return null
        if (!hasMagic(bytes)) return null
        if ((bytes[VERSION_INDEX].toInt() and 0xFF) != FORMAT_VERSION) return null
        if (readInt(bytes, LENGTH_BYTES - CHECKSUM_BYTES) != checksum(bytes, 0, LENGTH_BYTES - CHECKSUM_BYTES)) {
            return null
        }

        val failures = readInt(bytes, FAILURES_INDEX)
        val blockedUntil = readLong(bytes, BLOCKED_UNTIL_INDEX)
        if (failures < 0 || blockedUntil < 0L) return null

        return try {
            ThrottleState(consecutiveFailures = failures, blockedUntilMillis = blockedUntil)
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    private fun hasMagic(bytes: ByteArray): Boolean {
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return false
        }
        return true
    }
}
