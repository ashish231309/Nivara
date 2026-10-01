package com.nivara.app.domain.vault

/**
 * A document the user selected, as the import needs it: a name, what the provider said about it, and
 * bytes that can be read in pieces.
 *
 * Deliberately not a `File`, a `Uri`, a `DocumentFile` or an `InputStream`: the import pipeline is
 * Android-free and testable on the JVM, and the platform shapes stay in the adapter that implements
 * this. [read] fills a caller-supplied buffer, so nothing here can be asked for a whole file and the
 * pipeline decides how much memory it is willing to use.
 *
 * Implementations throw a typed [VaultImportFailure] from [read] when the source fails — the
 * pipeline turns that into the failure the screen shows.
 */
interface VaultContentSource {

    /** What the provider calls the file. Validated before anything is written; never a path. */
    val displayName: String

    /** What the provider declared, or `null` when it declared nothing usable. */
    val mimeType: String?

    /** How large the provider says the file is, or `null` when it does not say. */
    val declaredSizeBytes: Long?

    /**
     * Reads up to `buffer.size` bytes into [buffer].
     *
     * @return the number of bytes read, or `-1` when the source has ended.
     */
    suspend fun read(buffer: ByteArray): Int

    /** Releases anything the source is holding. Called whether the import succeeded or failed. */
    suspend fun close()
}
