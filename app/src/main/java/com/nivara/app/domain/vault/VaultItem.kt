package com.nivara.app.domain.vault

/**
 * One file that has been imported into the vault.
 *
 * Deliberately small: it carries what is needed to *find and open* the item's encrypted object later
 * and to describe it to its owner, and nothing about what the file contains. Albums, tags,
 * favourites, thumbnails, media duration, image dimensions, search ranking, trash state and restore
 * state are absent on purpose — they belong to the stages that will use them, and a field nothing
 * reads is a claim Nivara cannot back up.
 *
 * The original file itself is not here: the source may have been moved or deleted since the import,
 * and the item must not depend on it. What is here is where the *encrypted copy* lives, which is
 * [id] — the object is named after it.
 *
 * @property id the stable identifier, created at import and never reused.
 * @property name the original file name, kept for the owner to recognise the file by. It is
 *   authenticated index data and never becomes a path: nothing in the vault is named after it.
 * @property mimeType the type the source provider declared, when it declared one. `null` means the
 *   provider did not say, which is not the same as "unknown type".
 * @property sizeBytes the size of the original file in bytes.
 * @property importedAtEpochMillis when the import completed, in milliseconds since the epoch. Used to
 *   show the owner when a file arrived; it is not a security decision input.
 * @property contentFormatVersion the version of the encrypted content format this item was written
 *   in, so a later stage can refuse to read a format it does not know.
 * @property contentDigest the digest of the encrypted object on storage, recorded so that the object
 *   can be checked without a key.
 */
data class VaultItem(
    val id: VaultItemId,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val importedAtEpochMillis: Long,
    val contentFormatVersion: Int,
    val contentDigest: VaultContentDigest,
) {

    init {
        require(VaultItemNames.isWellFormed(name)) { "an item name is usable text of a bounded length" }
        require(mimeType == null || VaultItemNames.isWellFormedMimeType(mimeType)) {
            "an item's type is a bounded, printable type name"
        }
        require(sizeBytes >= 0) { "a file cannot be smaller than nothing" }
        require(importedAtEpochMillis >= 0) { "an import time is an instant after the epoch" }
        require(contentFormatVersion >= 1) { "a content format version starts at one" }
    }

    override fun toString(): String =
        "VaultItem(id=REDACTED, name=<redacted>, mimeType=$mimeType, sizeBytes=$sizeBytes, " +
            "contentFormatVersion=$contentFormatVersion, contentDigest=$contentDigest)"
}

/**
 * The rules a file name must satisfy to be kept as an item name.
 *
 * The name is never used to address anything — an item's encrypted object is named after its
 * [VaultItemId] — but it is stored, shown and later searched, so it has to be a *name* rather than
 * whatever bytes a provider chose to hand over: bounded, valid text, free of control characters, and
 * without path separators or traversal sequences, so that no reader can ever mistake it for a path
 * even by accident.
 */
object VaultItemNames {

    /** The longest name Nivara will store. Long enough for real file names, short enough to bound. */
    const val MAXIMUM_LENGTH: Int = 200

    /** The longest type name Nivara will store. Types are short; a long one is not a type. */
    const val MAXIMUM_MIME_LENGTH: Int = 128

    private val FORBIDDEN_CHARACTERS = charArrayOf('/', '\\', '\u0000')

    private const val TRAVERSAL = ".."

    /**
     * Whether [name] is a usable file name.
     *
     * Rejected: empty or blank names, names that are only dots, names longer than
     * [MAXIMUM_LENGTH] characters, names containing a path separator, a NUL, or any other control
     * character, and names containing a directory-traversal sequence.
     */
    fun isWellFormed(name: String): Boolean {
        if (name.isBlank() || name.length > MAXIMUM_LENGTH) return false
        if (name == "." || name == TRAVERSAL) return false
        if (name.contains(TRAVERSAL)) return false
        if (name.any { character -> character in FORBIDDEN_CHARACTERS }) return false
        return name.none { character -> character.isISOControl() }
    }

    /**
     * Whether [mimeType] is a usable type name.
     *
     * A provider may declare anything; what Nivara keeps is a short, printable, slash-separated type
     * without parameters. Anything else is dropped rather than stored, and the item is imported with
     * no declared type — which is an honest "the provider did not give a usable one".
     */
    fun isWellFormedMimeType(mimeType: String): Boolean {
        if (mimeType.isBlank() || mimeType.length > MAXIMUM_MIME_LENGTH) return false
        if (mimeType.count { character -> character == '/' } != 1) return false
        if (mimeType.startsWith('/') || mimeType.endsWith('/')) return false
        return mimeType.none { character -> character.isWhitespace() || character.isISOControl() }
    }

    /**
     * The name to store for a source that declared [name], or `null` when it is not usable.
     *
     * A provider may hand over a path-shaped or control-character-laden string; this keeps the last
     * path segment when there is one, so "a folder/picture.jpg" becomes "picture.jpg", and then
     * applies the rules above. Nothing is repaired beyond that: a name that is still not usable is
     * refused, and the import fails rather than inventing one.
     */
    fun sanitize(name: String): String? {
        val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')
        return if (isWellFormed(lastSegment)) lastSegment else null
    }
}
