package com.nivara.app.data.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultLocation
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The vault's content area on Android, through the Storage Access Framework.
 *
 * ### Writing an object without ever exposing a half-written one
 *
 * The platform cannot replace a document atomically, and it cannot append to one safely, so an
 * object is written the only way that is safe here:
 *
 * 1. any leftover from an earlier attempt at the same name is removed, and the object is created
 *    under its **pending** name — a name no reader treats as an item;
 * 2. the ciphertext is streamed into it and synced towards storage;
 * 3. the document is renamed to the item's own name, which is what makes it an object: readers look
 *    for completed names only, so an interruption at any point before this leaves a pending object
 *    that no list will ever show as a file;
 * 4. the caller reads it back and checks it, and only then is it named in the index.
 *
 * A [produce] that throws — an interrupted read, a cancelled import, a storage failure — removes the
 * pending document before the failure is reported. A rename the provider refuses removes it too: a
 * completed-looking object that is not where it says it is would be worse than no object at all.
 *
 * ### What is deliberately not here
 *
 * No deletion of content, no listing of a *whole* vault, no reading of an object's insides: this
 * class moves bytes for the object it is told about, and the repository above it decides what an
 * object means. Like the metadata storage, it never reports an empty listing for a failure — "I
 * could not look" and "there is nothing there" must not be the same answer anywhere in the vault.
 */
internal class SafVaultContentStorage(
    private val context: Context,
    private val location: VaultLocation,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VaultContentStorage {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun listObjects(): NivaraResult<List<ContentObjectRef>> =
        withContext(dispatcher) {
            nivaraRunCatching {
                val directory = directoryUriOrThrow(VaultStructure.CONTENT_DIRECTORY)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    Uri.parse(location.reference),
                    DocumentsContract.getDocumentId(directory),
                )
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                // A provider that does not answer is unreachable storage, never an empty area.
                val cursor = resolver.query(children, projection, null, null, null)
                    ?: throw VaultRootException(VaultFailure.StorageUnavailable)
                val entries = mutableListOf<ContentObjectRef>()
                cursor.use { result ->
                    while (result.moveToNext()) {
                        val name = result.getString(0) ?: continue
                        // The content area has no nested structure; a directory in it is somebody's
                        // own folder and not an object of Nivara's.
                        if (result.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            entries += ContentObjectRef.Foreign(name = name, sizeBytes = null)
                            continue
                        }
                        val size = if (result.isNull(1)) null else result.getLong(1)
                        entries += VaultContentNames.classify(name = name, sizeBytes = size)
                    }
                }
                entries
            }.mapVaultFailure()
        }

    override suspend fun writeObject(
        itemId: VaultItemId,
        produce: suspend (OutputStream) -> Unit,
    ): NivaraResult<Unit> = withContext(dispatcher) {
        nivaraRunCatching {
            val directory = directoryUriOrThrow(VaultStructure.CONTENT_DIRECTORY)
            val pendingName = VaultContentNames.pendingName(itemId)
            val objectName = VaultContentNames.objectName(itemId)

            childUri(directory, pendingName)?.let { leftover ->
                // An object left by an earlier attempt under this name is not one this write may
                // build on, and the name belongs to this item alone.
                if (!DocumentsContract.deleteDocument(resolver, leftover)) {
                    throw VaultRootException(VaultFailure.WriteFailed)
                }
            }
            val pending = DocumentsContract.createDocument(
                resolver,
                directory,
                OCTET_STREAM,
                pendingName,
            ) ?: throw VaultRootException(VaultFailure.WriteFailed)

            val descriptor = resolver.openFileDescriptor(pending, "w")
                ?: throw VaultRootException(VaultFailure.WriteFailed)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    try {
                        produce(output)
                        output.flush()
                        output.fd.sync()
                    } catch (failure: Exception) {
                        // The stream is closed by `use`, and the pending document is removed below:
                        // a failed write must not leave something that looks like a partial object.
                        throw failure
                    }
                }
            } catch (failure: Exception) {
                DocumentsContract.deleteDocument(resolver, pending)
                throw failure
            }

            // Only now may the object take the name a reader trusts. A name that is somehow already
            // taken is removed first: an item's name belongs to that item, and every import has a
            // fresh identifier.
            childUri(directory, objectName)?.let { existing ->
                if (!DocumentsContract.deleteDocument(resolver, existing)) {
                    DocumentsContract.deleteDocument(resolver, pending)
                    throw VaultRootException(VaultFailure.WriteFailed)
                }
            }
            val renamed = DocumentsContract.renameDocument(resolver, pending, objectName)
            if (renamed == null) {
                // A provider that will not rename leaves no object at all rather than one under a
                // pending name that looks like work in progress forever.
                DocumentsContract.deleteDocument(resolver, pending)
                throw VaultRootException(VaultFailure.WriteFailed)
            }
        }.mapVaultFailure(ioFailure = VaultFailure.WriteFailed)
    }

    override suspend fun readObject(
        entryName: String,
        consume: suspend (InputStream) -> Unit,
    ): NivaraResult<Unit> = withContext(dispatcher) {
        nivaraRunCatching {
            val directory = directoryUriOrThrow(VaultStructure.CONTENT_DIRECTORY)
            val document = childUri(directory, entryName)
                ?: throw VaultRootException(VaultFailure.StorageUnavailable)
            val stream = resolver.openInputStream(document)
                ?: throw VaultRootException(VaultFailure.StorageUnavailable)
            stream.use { input -> consume(input) }
        }.mapVaultFailure()
    }

    override suspend fun deleteObject(entryName: String): NivaraResult<Unit> =
        withContext(dispatcher) {
            nivaraRunCatching {
                val directory = directoryUriOrThrow(VaultStructure.CONTENT_DIRECTORY)
                val existing = childUri(directory, entryName)
                if (existing != null && !DocumentsContract.deleteDocument(resolver, existing)) {
                    throw VaultRootException(VaultFailure.WriteFailed)
                }
            }.mapVaultFailure(ioFailure = VaultFailure.WriteFailed)
        }

    /** The document of a directory inside the root, or `null` when it is not there. */
    private fun directoryUri(name: String): Uri? {
        val uri = childUri(rootDocumentUri(), name) ?: return null
        val mimeType = resolver.getType(uri)
        if (mimeType != null && mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            // Something carries a name Nivara uses and is not a directory: the root is there and what
            // is inside it is wrong, which is a broken structure rather than a bad selection.
            throw VaultRootException(VaultFailure.StorageUnavailable)
        }
        return uri
    }

    private fun directoryUriOrThrow(name: String): Uri =
        directoryUri(name) ?: throw VaultRootException(VaultFailure.StorageUnavailable)

    /** The document URI of the folder the user selected. Resolved per call, never cached. */
    private fun rootDocumentUri(): Uri {
        val tree = Uri.parse(location.reference)
        if (!DocumentsContract.isTreeUri(tree)) throw VaultRootException(VaultFailure.InvalidLocation)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(tree)
        return DocumentsContract.buildDocumentUriUsingTree(tree, treeDocumentId)
    }

    /** The child document named [displayName] under [directory], or `null` when it is not there. */
    private fun childUri(directory: Uri, displayName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            Uri.parse(location.reference),
            DocumentsContract.getDocumentId(directory),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        // A provider that does not answer is unreachable storage, never an absent document.
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
        return null
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}
