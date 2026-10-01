package com.nivara.app.data.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultUnreadable
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The vault root on Android, through the Storage Access Framework.
 *
 * ### Why SAF
 *
 * The vault has to live where the user can see it — on an SD card, in a synced folder, in a folder
 * they choose — and not inside Nivara's private directory. On Android 9 that could be done with a
 * filesystem path and a storage permission; from Android 10 the platform stopped granting apps
 * general access to shared storage, and `MANAGE_EXTERNAL_STORAGE` is a permission for file managers,
 * not for a vault. The Storage Access Framework is the mechanism Android provides for exactly this
 * case: the user picks a folder, the app is granted durable access to *that folder only*, and no
 * broad permission is involved. Nivara therefore asks for no storage permission at all.
 *
 * ### What SAF cannot do, and what this class does instead
 *
 * SAF has no atomic rename-over-existing and no truncate-in-place that a reader can trust.
 * `DocumentsContract.createDocument` may fail or auto-rename when a name is taken, and
 * `renameDocument` cannot overwrite. So [writeMetadata] is built out of three steps that are safe in
 * that environment:
 *
 * 1. delete the target document *if it is there* — the caller only ever targets the slot that is not
 *    the current record, so this never destroys the authoritative bytes;
 * 2. create a fresh document and write the bytes, flush and `fsync` them;
 * 3. the caller reads the document back and validates it before the write counts as committed.
 *
 * Step 3 is what makes "durable" mean something here: a provider that silently drops the write, or
 * auto-renames the document so the expected name is missing, is caught by the read-back rather than
 * reported as success.
 *
 * ### Typed trouble
 *
 * Every failure is one of the vault's own failure cases — unreachable storage, a refused grant, a
 * write that did not happen — and never a raw platform exception and never an empty result. The call
 * that decides whether a document exists returns `null` for "no such document" and a *failure* for
 * everything else, because a damaged answer must not be read as an absent vault.
 */
internal class SafVaultRootStorage(
    private val context: Context,
    private val location: VaultLocation,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VaultRootStorage {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * The document URI of the folder the user selected.
     *
     * Resolved once per call rather than cached: a grant can be revoked between two calls, and asking
     * the platform each time means a revoked grant fails with a refusal instead of being used from a
     * stale copy.
     */
    private fun rootDocumentUri(): Uri {
        val tree = Uri.parse(location.reference)
        if (!DocumentsContract.isTreeUri(tree)) throw VaultRootException(VaultFailure.InvalidLocation)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(tree)
        return DocumentsContract.buildDocumentUriUsingTree(tree, treeDocumentId)
    }

    override suspend fun ensureMetadataArea(): NivaraResult<Unit> =
        ensureDirectory(VaultStructure.METADATA_DIRECTORY)

    override suspend fun ensureContentArea(): NivaraResult<Unit> =
        ensureDirectory(VaultStructure.CONTENT_DIRECTORY)

    override suspend fun metadataAreaExists(): NivaraResult<Boolean> =
        withContext(dispatcher) {
            nivaraRunCatching { directoryUri(VaultStructure.METADATA_DIRECTORY) != null }
                .mapVaultFailure()
        }

    override suspend fun metadataEntries(): NivaraResult<List<String>> =
        entriesOf(VaultStructure.METADATA_DIRECTORY)

    override suspend fun contentAreaExists(): NivaraResult<Boolean> =
        withContext(dispatcher) {
            nivaraRunCatching { directoryUri(VaultStructure.CONTENT_DIRECTORY) != null }
                .mapVaultFailure()
        }

    override suspend fun readMetadata(entryName: String): NivaraResult<ByteArray> =
        withContext(dispatcher) {
            nivaraRunCatching {
                val directory = directoryUriOrThrow(VaultStructure.METADATA_DIRECTORY)
                val document = childUri(directory, entryName)
                    ?: throw VaultRootException(VaultFailure.StorageUnavailable)
                val stream = resolver.openInputStream(document)
                    ?: throw VaultRootException(VaultFailure.StorageUnavailable)
                stream.use { input ->
                    // Bounded: a metadata record has a known maximum, and reading whatever happens to
                    // be there would let a damaged or hostile document decide how much memory Nivara
                    // allocates. One byte past the bound is enough to know it is too large.
                    val buffer = ByteArray(VaultRecordCodec.MAXIMUM_RECORD_LENGTH + 1)
                    var read = 0
                    while (read < buffer.size) {
                        val count = input.read(buffer, read, buffer.size - read)
                        if (count <= 0) break
                        read += count
                    }
                    if (read > VaultRecordCodec.MAXIMUM_RECORD_LENGTH) {
                        throw VaultRootException(
                            VaultFailure.VaultUnreadable(VaultUnreadable.MetadataDamaged),
                        )
                    }
                    buffer.copyOf(read)
                }
            }.mapVaultFailure()
        }

    override suspend fun writeMetadata(
        entryName: String,
        bytes: ByteArray,
    ): NivaraResult<Unit> = withContext(dispatcher) {
        nivaraRunCatching {
            val directory = directoryUriOrThrow(VaultStructure.METADATA_DIRECTORY)
            // The caller only ever writes the slot that is not the current record, so removing it
            // cannot destroy the authoritative bytes. If it is somehow the current record, the
            // generation scheme means the remaining slot still wins on the next read.
            childUri(directory, entryName)?.let { existing ->
                if (!DocumentsContract.deleteDocument(resolver, existing)) {
                    throw VaultRootException(VaultFailure.WriteFailed)
                }
            }
            val created = DocumentsContract.createDocument(
                resolver,
                directory,
                OCTET_STREAM,
                entryName,
            ) ?: throw VaultRootException(VaultFailure.WriteFailed)
            // A provider is allowed to answer with a different name — several auto-uniquify instead of
            // failing. That is not checked here, because checking it is what the caller's read-back
            // already does: the record is read again *by slot name* and validated, so a write that
            // landed anywhere else is reported as a failed write rather than as a created vault.
            val stream = resolver.openOutputStream(created)
                ?: throw VaultRootException(VaultFailure.WriteFailed)
            stream.use { output -> output.writeAndSync(bytes) }
        }.mapVaultFailure(ioFailure = VaultFailure.WriteFailed)
    }

    override suspend fun deleteMetadata(entryName: String): NivaraResult<Unit> =
        withContext(dispatcher) {
            nivaraRunCatching {
                val directory = directoryUriOrThrow(VaultStructure.METADATA_DIRECTORY)
                childUri(directory, entryName)?.let { existing ->
                    if (!DocumentsContract.deleteDocument(resolver, existing)) {
                        throw VaultRootException(VaultFailure.WriteFailed)
                    }
                }
            }.mapVaultFailure(ioFailure = VaultFailure.WriteFailed)
        }

    private suspend fun ensureDirectory(name: String): NivaraResult<Unit> = withContext(dispatcher) {
        nivaraRunCatching {
            if (directoryUri(name) != null) return@nivaraRunCatching
            val root = rootDocumentUri()
            DocumentsContract.createDocument(resolver, root, DIRECTORY_MIME_TYPE, name)
                ?: throw VaultRootException(VaultFailure.WriteFailed)
            if (directoryUri(name) == null) {
                // Created, or so the provider said, but not findable by the name that matters. The
                // caller must not proceed as if the directory exists.
                throw VaultRootException(VaultFailure.WriteFailed)
            }
        }.mapVaultFailure(ioFailure = VaultFailure.WriteFailed)
    }

    private suspend fun entriesOf(name: String): NivaraResult<List<String>> =
        withContext(dispatcher) {
            nivaraRunCatching {
                val directory = directoryUri(name) ?: return@nivaraRunCatching emptyList()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    Uri.parse(location.reference),
                    DocumentsContract.getDocumentId(directory),
                )
                val entries = mutableListOf<String>()
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                // A provider that does not answer is unreachable storage, never an empty area.
                val cursor = resolver.query(children, projection, null, null, null)
                    ?: throw VaultRootException(VaultFailure.StorageUnavailable)
                cursor.use { result ->
                    while (result.moveToNext()) {
                        val displayName = result.getString(0) ?: continue
                        // A directory is not a document of a metadata or content area: neither area
                        // has nested structure, and reporting one would make the reader treat a folder
                        // as a record.
                        if (result.getString(1) == DIRECTORY_MIME_TYPE) continue
                        entries += displayName
                    }
                }
                entries
            }.mapVaultFailure()
        }

    /** The child document named [displayName] under [directory], or `null` when it is not there. */
    private fun childUri(directory: Uri, displayName: String): Uri? =
        childUriOrThrow(directory, displayName, missingIsNull = true)

    private fun childUriOrThrow(directory: Uri, displayName: String): Uri =
        childUriOrThrow(directory, displayName, missingIsNull = false)
            ?: throw VaultRootException(VaultFailure.StorageUnavailable)

    private fun childUriOrThrow(directory: Uri, displayName: String, missingIsNull: Boolean): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            Uri.parse(location.reference),
            DocumentsContract.getDocumentId(directory),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        // A provider that does not answer is unreachable storage, never an absent document: "I could
        // not look" and "it is not there" must not become the same answer anywhere in this class.
        val cursor = resolver.query(children, projection, null, null, null)
            ?: throw VaultRootException(VaultFailure.StorageUnavailable)
        cursor.use { result ->
            while (result.moveToNext()) {
                if (result.getString(1) == displayName) {
                    val documentId = result.getString(0) ?: continue
                    return DocumentsContract.buildDocumentUriUsingTree(
                        Uri.parse(location.reference),
                        documentId,
                    )
                }
            }
        }
        if (missingIsNull) return null
        throw VaultRootException(VaultFailure.StorageUnavailable)
    }

    /** The document of a directory inside the root, or `null` when it is not there. */
    private fun directoryUri(name: String): Uri? {
        val uri = childUriOrThrow(rootDocumentUri(), name, missingIsNull = true) ?: return null
        val mimeType = resolver.getType(uri)
        if (mimeType != null && mimeType != DIRECTORY_MIME_TYPE) {
            // Something carries a name Nivara uses and is not a directory. That is a broken vault
            // structure, not a bad selection: the root is there, and what is inside it is wrong.
            throw VaultRootException(
                VaultFailure.VaultUnreadable(VaultUnreadable.StructureIncomplete),
            )
        }
        return uri
    }

    private fun directoryUriOrThrow(name: String): Uri =
        directoryUri(name) ?: throw VaultRootException(VaultFailure.StorageUnavailable)

    /**
     * Writes and forces [bytes] towards storage before the stream is closed.
     *
     * The providers Nivara meets hand out descriptor-backed streams, which are synced. A provider
     * that hands out something else gets the flush it can honour, and the caller's read-back
     * verification is what decides whether the write counts: Nivara never reports a vault as created
     * because a call returned.
     */
    private fun OutputStream.writeAndSync(bytes: ByteArray) {
        write(bytes)
        flush()
        (this as? FileOutputStream)?.fd?.sync()
            ?: ((this as? FilterOutputStream)?.out as? FileOutputStream)?.fd?.sync()
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        const val DIRECTORY_MIME_TYPE = DocumentsContract.Document.MIME_TYPE_DIR
    }
}

/**
 * A vault failure travelling as an exception *inside* the data layer.
 *
 * The storage code is written with the project's `nivaraRunCatching`, which turns any exception into
 * a `Failure`, and the mapping back to a typed [VaultFailure] happens in one place
 * ([mapVaultFailure]). Platform exceptions that are not a vault failure get their own translation
 * there, so nothing platform-shaped survives the boundary and nothing is swallowed.
 */
internal class VaultRootException(val failure: VaultFailure) :
    Exception(failure.message, failure)

/**
 * Translates whatever the platform threw into the vault's own failure vocabulary.
 *
 * This is where "the grant was revoked" becomes [VaultFailure.AccessDenied] rather than an empty
 * result, and where an already-typed vault failure passes through unchanged.
 */
internal fun <T> NivaraResult<T>.mapVaultFailure(
    ioFailure: VaultFailure = VaultFailure.StorageUnavailable,
): NivaraResult<T> = when (this) {
    is NivaraResult.Success -> this
    is NivaraResult.Failure -> NivaraResult.Failure(
        when (val error = error) {
            is VaultRootException -> error.failure
            is SecurityException -> VaultFailure.AccessDenied
            is FileNotFoundException -> VaultFailure.StorageUnavailable
            // An IO failure means different things on the two sides of the port, and only the call
            // site knows which one it is: reading that cannot happen is unreachable storage, writing
            // that could not happen is a refused write.
            is IOException -> ioFailure
            else -> VaultFailure.StorageUnavailable
        },
    )
}
