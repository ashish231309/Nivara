package com.nivara.app.domain.vault

/**
 * Everything that can go wrong when a file is imported.
 *
 * Each case has a different next step, which is why they are separate values rather than one "import
 * failed": a source Nivara may not read, a vault that is not ready, an index that cannot be trusted,
 * a destination that refused the write and a platform key that is gone are five different situations
 * for the person holding the phone.
 *
 * Every case is also a promise about what did *not* happen. No case means "the file is in the vault
 * and something else went wrong": an import reports success only after its encrypted object has been
 * written, read back, checked and named in the index. Like every other typed failure in Nivara the
 * messages are fixed, non-secret strings — no path, no URI, no file name, no key material.
 */
sealed class VaultImportFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The user does not currently have a valid session.
     *
     * Importing writes to the vault, so it is authorized the way every other durable change is. The
     * answer is to unlock Nivara, not to authenticate somewhere else: there is no import password.
     */
    data object NotAuthorized :
        VaultImportFailure("importing needs an open session")

    /**
     * The vault is not ready to hold content: no folder chosen, a record that cannot be opened, a
     * newer format, unreachable storage.
     *
     * Nothing was written and nothing was changed. The vault's own state says what to do about it.
     */
    data class VaultNotReady(val vault: VaultState) :
        VaultImportFailure("the vault is not ready to import into")

    /**
     * The vault already holds an index record that cannot be read.
     *
     * Importing is refused rather than starting a second list: appending to a list Nivara cannot read
     * would replace it, and the items it names — files that are still in the vault — would become
     * unindexed. Nothing was written.
     */
    data class IndexUnreadable(val reason: VaultIndexUnreadable) :
        VaultImportFailure("the vault's index cannot be read")

    /**
     * The index was written by a newer Nivara.
     *
     * Never imported over: the newer format may describe items this build cannot see, and a later
     * version can still read it.
     */
    data class UnsupportedIndexVersion(val fileVersion: Int) :
        VaultImportFailure("the vault's index was written by a newer Nivara")

    /**
     * The index is full.
     *
     * The format bounds how many items one record can hold, and a record that cannot be written must
     * not be half-written. This is a limit of the format, reported honestly rather than worked
     * around; a later stage that needs more items raises the bound with a format version.
     */
    data object IndexFull :
        VaultImportFailure("the vault's index cannot hold another item")

    /** The document the user selected cannot be read: it was deleted, moved, or its provider is gone. */
    data object SourceUnavailable :
        VaultImportFailure("the selected document cannot be read")

    /** The platform no longer grants Nivara access to the selected document. */
    data object SourceAccessDenied :
        VaultImportFailure("access to the selected document was not granted")

    /**
     * The selected source is larger than Nivara will import ([MAXIMUM_SOURCE_BYTES]).
     *
     * The bound is not a product limit — it is far larger than any phone's free storage — but a
     * source that never ends would otherwise fill the vault with one file. It is checked from the
     * provider's declared size *and* while reading, because a provider's size is not a promise.
     */
    data object SourceTooLarge :
        VaultImportFailure("the selected document is larger than Nivara imports")

    /**
     * The source's name cannot be stored as an item name.
     *
     * The name is kept for its owner to recognise the file by, so a name that is not usable text —
     * empty, absurdly long, full of control characters or shaped like a path — is refused rather
     * than repaired into something that was never the file name.
     */
    data object InvalidSourceName :
        VaultImportFailure("the selected document's name cannot be stored")

    /** The vault root cannot be reached, so the object could not be written. */
    data object StorageUnavailable :
        VaultImportFailure("the vault's storage cannot be reached")

    /** The platform refuses access to the vault root. */
    data object AccessDenied :
        VaultImportFailure("access to the vault's storage was refused")

    /** The platform refused a write while the object or the index was being written. */
    data object WriteFailed :
        VaultImportFailure("the vault's storage refused a write")

    /**
     * Something written did not come back as it was written.
     *
     * The object or the index was read again after writing and did not match: Nivara reports a
     * failure rather than claiming a file is in the vault when the bytes on storage say otherwise.
     */
    data object VerificationFailed :
        VaultImportFailure("what was written did not verify")

    /** The platform key that protects the vault's key material is gone or unusable. */
    data object KeyUnavailable :
        VaultImportFailure("the platform key protecting the vault is not available")

    /** One of the existing cryptographic services refused the operation. */
    data object CryptographyFailed :
        VaultImportFailure("a cryptographic service refused the operation")

    /**
     * The identifier generated for this import was already in use.
     *
     * Impossible by construction — ids are 128 random bits and checked against the index — and
     * reported rather than retried, because a collision would mean the generator is not the secure
     * one Nivara requires.
     */
    data object DuplicateItemId :
        VaultImportFailure("the generated item identifier was already in use")

    companion object {

        /**
         * The largest source Nivara will import: 16 GiB.
         *
         * A guard against a source that never ends, not a limit anyone will meet — the largest
         * phones hold less than this in total.
         */
        const val MAXIMUM_SOURCE_BYTES: Long = 16L * 1024 * 1024 * 1024
    }
}
