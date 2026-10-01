package com.nivara.app.data.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.vault.VaultContentSource
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultSourceReference
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the document the user picked, and nothing else.
 *
 * The Storage Access Framework hands Nivara a reference and a temporary grant; this class turns that
 * into a [VaultContentSource] — a name, a declared type, a declared size, and bytes read in pieces.
 * It is one of the two places in the vault that knows what a platform document is, and it holds the
 * reference for the duration of one import: nothing here is stored, and nothing here is remembered
 * afterwards.
 *
 * ### What is trusted, and what is not
 *
 * A provider's metadata is a claim, not a fact. The name is taken from the document's own columns when
 * the provider offers them, the type from the platform's resolver, and the size only when the provider
 * declares a usable one. All three are validated before anything is written, and the declared **size
 * never decides how much is read**: a provider that under-reports it cannot make Nivara stop early,
 * and one that over-reports it cannot make Nivara allocate anything.
 *
 * ### What this class never does
 *
 * It never lists a directory, never walks a tree, never writes to the source and never keeps a path.
 * The document is read once, during the import, and the source is left exactly as it was found — the
 * vault keeps its own encrypted copy, and the imported item must keep working after the source has
 * been moved, renamed or deleted.
 */
internal class SafDocumentSourceOpener(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VaultSourceOpener {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun open(reference: VaultSourceReference): NivaraResult<VaultContentSource> =
        withContext(dispatcher) {
            nivaraRunCatching {
                val uri = Uri.parse(reference.value)
                val stream = resolver.openInputStream(uri)
                    ?: throw VaultSourceException(VaultImportFailure.SourceUnavailable)
                val metadata = readMetadata(uri)
                SafDocumentSource(
                    stream = stream,
                    displayName = metadata.displayName,
                    mimeType = metadata.mimeType,
                    declaredSizeBytes = metadata.sizeBytes,
                    dispatcher = dispatcher,
                )
            }.mapToImportFailure()
        }

    /**
     * What the provider says about the document.
     *
     * Only the document's own columns are consulted — never a path, never a guess from the
     * reference — and a column the provider does not fill in stays "not said" rather than being
     * invented.
     */
    private fun readMetadata(uri: Uri): DocumentMetadata {
        var displayName: String? = null
        var size: Long? = null
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        // Providers report a negative value for "not known". That is not a size, and
                        // it is treated exactly like a provider that said nothing.
                        val reported = cursor.getLong(sizeIndex)
                        if (reported >= 0) size = reported
                    }
                }
            }
        } catch (missing: FileNotFoundException) {
            throw VaultSourceException(VaultImportFailure.SourceUnavailable)
        }

        return DocumentMetadata(
            displayName = displayName ?: nameFromDocumentId(uri),
            mimeType = resolver.getType(uri),
            sizeBytes = size,
        )
    }

    /**
     * The name used when a provider names its documents not at all.
     *
     * The document's own identifier is reduced to its final segment, so a file with no declared name
     * still imports under something its owner can recognise. It is platform data rather than a path,
     * and it is validated by exactly the same rules as any other name before anything is written —
     * which is what makes "no name at all" impossible to turn into a bad one.
     */
    private fun nameFromDocumentId(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val candidate = (documentId ?: uri.lastPathSegment ?: "").substringAfterLast('/')
        return candidate.ifBlank { UNNAMED }
    }

    private data class DocumentMetadata(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long?,
    )

    private companion object {

        /** The name given to a document whose provider names nothing at all. */
        const val UNNAMED = "unnamed"
    }
}

/**
 * One open source document.
 *
 * The stream is the platform's, read into the caller's buffer, and closed once when the import ends —
 * whether it succeeded, failed or was refused. The known file sources are the only exception the
 * platform throws for a document that is gone or unreadable, and each is reported as the failure it
 * is rather than as the end of the file: a source that stops being readable must fail the import, not
 * silently shorten it.
 */
internal class SafDocumentSource(
    private val stream: InputStream,
    override val displayName: String,
    override val mimeType: String?,
    override val declaredSizeBytes: Long?,
    private val dispatcher: CoroutineDispatcher,
) : VaultContentSource {

    override suspend fun read(buffer: ByteArray): Int = withContext(dispatcher) {
        try {
            stream.read(buffer)
        } catch (denied: SecurityException) {
            // A grant can be withdrawn while a document is open. That is a refusal, not the end of
            // the file, and it must fail the import rather than import a shorter one.
            throw VaultSourceException(VaultImportFailure.SourceAccessDenied)
        } catch (unreadable: IOException) {
            // Every platform failure that means "this document cannot be read any more" is a source
            // failure: it must stop the import, never shorten it.
            throw VaultSourceException(VaultImportFailure.SourceUnavailable)
        }
    }

    override suspend fun close() {
        withContext(dispatcher) {
            // Closing is best effort: a provider that refuses to close a stream has no bearing on
            // whether the encrypted object was written.
            runCatching { stream.close() }
        }
    }
}
