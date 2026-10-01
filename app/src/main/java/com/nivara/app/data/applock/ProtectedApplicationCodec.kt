package com.nivara.app.data.applock

import com.nivara.app.data.credential.checksum
import com.nivara.app.data.credential.readInt
import com.nivara.app.data.credential.writeInt
import com.nivara.app.domain.applock.PackageNames
import com.nivara.app.domain.applock.ProtectedApplication

/**
 * The on-disk form of the protected application set.
 *
 * The format is a fixed, versioned byte layout rather than a serialization framework, for the same
 * reasons the credential record has one: it is small, it has no dependencies, and every field is
 * explicit, so a change is a version bump instead of a surprise.
 *
 * ```
 *  offset  size  field
 *  ------  ----  ---------------------------------------------------------------
 *       0     4  magic "NVPL"
 *       4     1  format version (1)
 *       5     4  protected application count, big-endian
 *       9     n  each application: 1-byte name length + UTF-8 package name
 *   9+n      4  CRC-32 of everything before it, big-endian
 * ```
 *
 * Two properties matter more than the layout. The file is **deterministic** — the applications are
 * written sorted by package name, so the same set always produces the same bytes — and it contains
 * **nothing but package names**. No labels, no icons, no counts of use, no timestamps and no
 * authentication material: a package name is what protection needs and the whole of what is stored.
 *
 * Decoding is strict. An unknown magic, an unknown version, a checksum mismatch, an oversized
 * count, a name that is not a usable package name, a duplicate, or a trailing byte all produce
 * `null`, which the repository turns into
 * [com.nivara.app.domain.applock.AppLockFailure.ProtectedApplicationsUnreadable]. Nothing is
 * guessed and nothing is repaired — and, importantly, a damaged file is never read as an empty set.
 * See [FileProtectedApplicationRepository] for why that is the security-relevant choice.
 */
internal object ProtectedApplicationCodec {

    /** Version of the set format written by this build. */
    const val FORMAT_VERSION: Int = 1

    /**
     * The largest set the format accepts.
     *
     * A device has a few hundred launchable applications, so a count beyond this is corruption
     * rather than configuration. The bound is what stops a damaged file from asking for an
     * enormous allocation before it has been rejected.
     */
    const val MAXIMUM_APPLICATIONS: Int = 10_000

    private const val MAGIC_BYTES = 4
    private const val VERSION_INDEX = MAGIC_BYTES
    private const val COUNT_INDEX = VERSION_INDEX + 1
    private const val HEADER_BYTES = COUNT_INDEX + 4
    private const val LENGTH_BYTES = 1
    private const val CHECKSUM_BYTES = 4

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte())

    /** Smallest possible file: header plus the checksum, with no applications. */
    const val MINIMUM_BYTES: Int = HEADER_BYTES + CHECKSUM_BYTES

    /** [applications] in their on-disk form, sorted by package name. */
    fun encode(applications: Collection<ProtectedApplication>): ByteArray {
        require(applications.size <= MAXIMUM_APPLICATIONS) {
            "the protected application set is larger than the format accepts"
        }
        val names = applications
            .map { application -> application.packageName }
            .distinct()
            .sorted()
            .map { packageName -> packageName.toByteArray(Charsets.UTF_8) }

        val namesBytes = names.sumOf { name -> LENGTH_BYTES + name.size }
        val buffer = ByteArray(HEADER_BYTES + namesBytes + CHECKSUM_BYTES)

        MAGIC.copyInto(buffer, 0)
        buffer[VERSION_INDEX] = FORMAT_VERSION.toByte()
        writeInt(buffer, COUNT_INDEX, names.size)

        var offset = HEADER_BYTES
        names.forEach { name ->
            buffer[offset] = name.size.toByte()
            name.copyInto(buffer, offset + LENGTH_BYTES)
            offset += LENGTH_BYTES + name.size
        }

        writeInt(buffer, offset, checksum(buffer, 0, offset))
        return buffer
    }

    /** The protected applications in [bytes], or `null` when the bytes are not a valid set. */
    fun decode(bytes: ByteArray): Set<ProtectedApplication>? {
        if (bytes.size < MINIMUM_BYTES) return null
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return null
        }
        if (bytes[VERSION_INDEX].toInt() != FORMAT_VERSION) return null

        val count = readInt(bytes, COUNT_INDEX)
        if (count < 0 || count > MAXIMUM_APPLICATIONS) return null

        val contentEnd = bytes.size - CHECKSUM_BYTES
        if (readInt(bytes, contentEnd) != checksum(bytes, 0, contentEnd)) return null

        val applications = LinkedHashSet<ProtectedApplication>()
        var offset = HEADER_BYTES
        repeat(count) {
            if (offset + LENGTH_BYTES > contentEnd) return null
            val length = bytes[offset].toInt() and 0xFF
            offset += LENGTH_BYTES
            if (length < 1 || offset + length > contentEnd) return null
            val packageName = String(bytes, offset, length, Charsets.UTF_8)
            offset += length
            if (!PackageNames.isUsable(packageName)) return null
            applications += ProtectedApplication(packageName)
        }
        // Every byte before the checksum belongs to a name, and no name was repeated.
        if (offset != contentEnd || applications.size != count) return null

        return applications
    }
}
