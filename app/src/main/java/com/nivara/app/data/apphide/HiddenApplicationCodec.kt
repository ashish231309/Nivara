package com.nivara.app.data.apphide

import com.nivara.app.data.credential.checksum
import com.nivara.app.data.credential.readInt
import com.nivara.app.data.credential.writeInt
import com.nivara.app.domain.app.PackageNames
import com.nivara.app.domain.apphide.HiddenApplication

/**
 * The on-disk form of the hidden application set.
 *
 * The format is a fixed, versioned byte layout rather than a serialization framework, for the same
 * reasons the credential record and the protected set have one: it is small, it has no
 * dependencies, and every field is explicit, so a change is a version bump instead of a surprise.
 *
 * ```
 *  offset  size  field
 *  ------  ----  ---------------------------------------------------------------
 *       0     4  magic "NVHA"
 *       4     1  format version (1)
 *       5     4  hidden application count, big-endian
 *       9     n  each application: 1-byte name length + UTF-8 package name
 *   9+n      4  CRC-32 of everything before it, big-endian
 * ```
 *
 * ### Why this is its own format rather than a copy of App Lock's
 *
 * The two files answer different questions — which applications need authenticating, and which
 * applications Nivara should keep out of sight — and they will not necessarily evolve together. A
 * hidden set concerns a launcher that does not exist yet; the protected set concerns detection that
 * already does. Sharing one format would couple two features' version numbers and force a reader to
 * know about the other feature to understand either. So the shape is deliberately the same and the
 * format is deliberately separate: the same magic-version-count-checksum skeleton, a different
 * magic, and a different document explaining it. What they *do* share is the byte-level helpers
 * (`writeInt`, `readInt`, `checksum`) and nothing else — no mutable state, no cache and no codec
 * object.
 *
 * ### Two properties that matter more than the layout
 *
 * The file is **deterministic** — the applications are written sorted by package name and
 * de-duplicated, so the same set always produces the same bytes — and it contains **nothing but
 * package names**. No labels, no icons, no timestamps, no usage counts and no authentication
 * material: a package name is what hiding needs and the whole of what is stored. Nothing here is
 * encrypted, and nothing here is claimed to be secret: the file lives in the application's private
 * directory, which keeps other applications out, and an application's own files are not a place to
 * conceal anything from the device's owner or from a privileged tool.
 *
 * Decoding is strict. An unknown magic, an unknown version, a checksum mismatch, an oversized
 * count, a name that is not a usable package name, a duplicate, or a trailing byte all produce
 * `null`, which the repository turns into
 * [com.nivara.app.domain.apphide.HiddenApplicationsRead.Unreadable]. Nothing is guessed and nothing
 * is repaired — and, importantly, a damaged file is never read as an empty set. See
 * [FileHiddenApplicationRepository] for why that is the security-relevant choice.
 */
internal object HiddenApplicationCodec {

    /** Version of the hidden-set format written by this build. */
    const val FORMAT_VERSION: Int = 1

    /**
     * The largest set the format accepts.
     *
     * A device has a few hundred launchable applications, so a count beyond this is corruption
     * rather than configuration. The bound is what stops a damaged file from asking for an enormous
     * allocation before it has been rejected.
     */
    const val MAXIMUM_APPLICATIONS: Int = 10_000

    private const val MAGIC_BYTES = 4
    private const val VERSION_INDEX = MAGIC_BYTES
    private const val COUNT_INDEX = VERSION_INDEX + 1
    private const val HEADER_BYTES = COUNT_INDEX + 4
    private const val LENGTH_BYTES = 1
    private const val CHECKSUM_BYTES = 4

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'H'.code.toByte(), 'A'.code.toByte())

    /** Smallest possible file: header plus the checksum, with no applications. */
    const val MINIMUM_BYTES: Int = HEADER_BYTES + CHECKSUM_BYTES

    /** [applications] in their on-disk form, sorted by package name. */
    fun encode(applications: Collection<HiddenApplication>): ByteArray {
        require(applications.size <= MAXIMUM_APPLICATIONS) {
            "the hidden application set is larger than the format accepts"
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

    /** The hidden applications in [bytes], or `null` when the bytes are not a valid set. */
    fun decode(bytes: ByteArray): Set<HiddenApplication>? {
        if (bytes.size < MINIMUM_BYTES) return null
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return null
        }
        if (bytes[VERSION_INDEX].toInt() != FORMAT_VERSION) return null

        val count = readInt(bytes, COUNT_INDEX)
        if (count < 0 || count > MAXIMUM_APPLICATIONS) return null

        val contentEnd = bytes.size - CHECKSUM_BYTES
        if (readInt(bytes, contentEnd) != checksum(bytes, 0, contentEnd)) return null

        val applications = LinkedHashSet<HiddenApplication>()
        var offset = HEADER_BYTES
        repeat(count) {
            if (offset + LENGTH_BYTES > contentEnd) return null
            val length = bytes[offset].toInt() and 0xFF
            offset += LENGTH_BYTES
            if (length < 1 || offset + length > contentEnd) return null
            val packageName = String(bytes, offset, length, Charsets.UTF_8)
            offset += length
            if (!PackageNames.isUsable(packageName)) return null
            applications += HiddenApplication(packageName)
        }
        // Every byte before the checksum belongs to a name, and no name was repeated.
        if (offset != contentEnd || applications.size != count) return null

        return applications
    }
}
