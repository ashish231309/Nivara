package com.nivara.app.domain.vault

import com.nivara.app.domain.security.StreamSource

/**
 * A document the user selected, as the import needs it: a name, what the provider said about it, and
 * bytes that can be read in pieces.
 *
 * Deliberately not a `File`, a `Uri`, a `DocumentFile` or a stream type: the import pipeline is
 * Android-free and testable on the JVM, and the platform shapes stay in the adapter that implements
 * this. The bytes arrive through [read], which fills a caller-supplied buffer — so nothing here can
 * be asked for a whole file, and the pipeline decides how much memory it is willing to use.
 *
 * A source that stops being readable part way through must throw rather than end early: a shortened
 * file that still authenticated would be worse than a failed import. Implementations report that as
 * the [VaultImportFailure] it is.
 */
interface VaultContentSource : StreamSource {

    /** What the provider calls the file. Validated before anything is written; never a path. */
    val displayName: String

    /** What the provider declared, or `null` when it declared nothing usable. */
    val mimeType: String?

    /** How large the provider says the file is, or `null` when it does not say. */
    val declaredSizeBytes: Long?

    /** Releases anything the source is holding. Called once, whether the import succeeded or failed. */
    suspend fun close()
}
