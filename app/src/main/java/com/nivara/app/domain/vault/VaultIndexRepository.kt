package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult

/**
 * The vault's catalogue of imported files.
 *
 * Two operations, and both are about the same thing: what the vault holds. [read] answers it without
 * changing anything, and [importFile] adds one file — the only way content enters the vault in this
 * stage. Deleting an item is deliberately absent: it belongs to the stage that owns trash and
 * restore, and a delete that nothing asks for is a delete that can lose a file.
 *
 * An item never exists in one place only. Importing writes the encrypted object first, checks what
 * came back, and only then names it in the index, so a crash, a full disk or a platform refusal can
 * leave an object that no list mentions but can never put a file in the list that is not there.
 */
interface VaultIndexRepository {

    /**
     * Reports what the vault holds.
     *
     * Reads only: an index that cannot be read is reported as such and left exactly as it was found,
     * never rebuilt, never repaired and never replaced with an empty one.
     */
    suspend fun read(): VaultIndexState

    /**
     * Imports one document into the vault.
     *
     * The document is read once, in bounded pieces, and never held whole in memory. It is not
     * modified or deleted: the source stays where it is, and the vault keeps its own encrypted copy.
     *
     * @param source the document the user selected, as an opaque platform reference. It is used for
     *   this import only and is not stored.
     * @param authorize asked at the moment a durable change would be made — before the import starts
     *   and again before the index is committed. Returning `false` refuses the import and leaves
     *   nothing behind; the policy behind it (the existing session) belongs to the caller, which is
     *   why it arrives as a function rather than as a dependency of this layer.
     * @param onProgress called with the plaintext bytes processed so far. It never affects what is
     *   written or how it is authenticated.
     * @return the item as it was committed to the index, so the caller can show it without a second
     *   read.
     */
    suspend fun importFile(
        source: VaultSourceReference,
        authorize: () -> Boolean,
        onProgress: (VaultImportProgress) -> Unit = {},
    ): NivaraResult<VaultItem>
}

/**
 * How far an import has come.
 *
 * @param bytesProcessed plaintext bytes read from the source and encrypted so far.
 * @param totalBytes the source's declared size when the provider gave a usable one, or `null` when
 *   the import can only be shown as running. A declared size is a hint, never a rule: it does not
 *   bound what is read and it does not decide when the import is finished.
 */
data class VaultImportProgress(
    val bytesProcessed: Long,
    val totalBytes: Long?,
)
