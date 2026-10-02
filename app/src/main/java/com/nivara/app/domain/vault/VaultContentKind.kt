package com.nivara.app.domain.vault

/**
 * What an imported file is, as far as the type the index authenticated says.
 *
 * The classification is *presentation*: it decides which viewer Nivara offers and which icon the
 * list draws. The stored item is untouched by it — the index records what the provider declared and
 * the bytes, never how a screen chose to draw them — so a later stage may classify something
 * differently without any file being rewritten.
 *
 * [Other] is deliberately not [Document]: a document is a file Nivara recognises as a document even
 * when it has no viewer for it, while [Other] is a file whose declared type says nothing Nivara
 * understands. Both are readable and both are shown with their metadata; the difference is what the
 * screen may claim about them.
 */
enum class VaultContentKind {

    /** A still image Nivara can decode. */
    Image,

    /** A video the platform's media stack can play. */
    Video,

    /** Audio the platform's media stack can play. */
    Audio,

    /** A document: recognised as one, with a viewer where a safe one exists. */
    Document,

    /** A type Nivara does not recognise. Shown with its metadata and nothing claimed about it. */
    Other,
}

/**
 * The bounded set of declared types Nivara is willing to act on.
 *
 * ### Why a set rather than a rule
 *
 * "Render whatever the platform accepts" is not a policy: it makes what Nivara shows depend on the
 * device, the build and the codec pack of the moment, which is exactly the kind of behaviour that
 * cannot be documented honestly. The sets below are what Nivara claims, and everything outside them
 * is presented as a generic file with its metadata — never as a broken item, and never as a guess
 * from a file name.
 *
 * ### What the sets mean, and what they do not
 *
 * A type in a viewing set means Nivara *attempts* that viewer. It is not a promise that a particular
 * device decodes it: the decoding is the platform's, and a file the platform refuses becomes
 * [VaultContentFailure.Corrupt]-free "this file could not be shown" rather than a crash or an empty
 * vault. Audio and video are the platform's media stack (the `MediaPlayer` the platform ships);
 * images are the platform's image decoder.
 *
 * A file whose type is missing or malformed is classified from nothing at all — which is why it is
 * [VaultContentKind.Other] and not a guess from its extension: a name is user-supplied text, and the
 * whole point of the authenticated type is that it is not.
 */
object VaultContentClassification {

    /** Image types Nivara attempts to draw. */
    private val IMAGE_TYPES: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/bmp",
        "image/heic",
        "image/heif",
    )

    /** Video types Nivara hands to the platform's media stack. */
    private val VIDEO_TYPES: Set<String> = setOf(
        "video/mp4",
        "video/webm",
        "video/3gpp",
        "video/mpeg",
        "video/x-matroska",
    )

    /** Audio types Nivara hands to the platform's media stack. */
    private val AUDIO_TYPES: Set<String> = setOf(
        "audio/mpeg",
        "audio/mp4",
        "audio/aac",
        "audio/wav",
        "audio/x-wav",
        "audio/ogg",
        "audio/flac",
        "audio/x-flac",
        "audio/opus",
    )

    /** Document types Nivara can show as text. */
    private val TEXT_TYPES: Set<String> = setOf(
        "text/plain",
        "text/csv",
        "text/markdown",
        "text/xml",
        "application/json",
        "application/xml",
    )

    /** The one document type Nivara renders as a document. */
    private const val PDF_TYPE = "application/pdf"

    /**
     * Types recognised as documents even though this stage cannot render them.
     *
     * They are documents because the platform's own vocabulary says so — a Word file is a document
     * whatever Nivara can draw — and the screen says plainly that it cannot show one rather than
     * pretending the file is something else.
     */
    private val UNRENDERED_DOCUMENT_TYPES: Set<String> = setOf(
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/rtf",
        "text/rtf",
        "application/zip",
        "application/x-tar",
        "application/gzip",
    )

    /** The prefix every OpenXML document type shares. */
    private const val OPENXML_PREFIX = "application/vnd.openxmlformats-officedocument."

    /**
     * The kind of a declared type, or [VaultContentKind.Other] when there is nothing trustworthy to
     * go on.
     *
     * The declared type is normalised first — lower case, parameters such as `; charset=utf-8`
     * removed — and a type that is not well formed after that is treated exactly like a missing one.
     * A file name is never consulted: `holiday.jpg` with no declared type is [VaultContentKind.Other].
     */
    fun kindOf(mimeType: String?): VaultContentKind {
        val type = normalize(mimeType) ?: return VaultContentKind.Other
        return when {
            type.startsWith("image/") -> VaultContentKind.Image
            type.startsWith("video/") -> VaultContentKind.Video
            type.startsWith("audio/") -> VaultContentKind.Audio
            type.startsWith("text/") -> VaultContentKind.Document
            type in UNRENDERED_DOCUMENT_TYPES -> VaultContentKind.Document
            type.startsWith(OPENXML_PREFIX) -> VaultContentKind.Document
            type == PDF_TYPE -> VaultContentKind.Document
            else -> VaultContentKind.Other
        }
    }

    /** Whether Nivara will attempt to draw this item as an image. */
    fun isViewableImage(mimeType: String?): Boolean = normalize(mimeType) in IMAGE_TYPES

    /** Whether Nivara will hand this item to the platform's media stack as video. */
    fun isPlayableVideo(mimeType: String?): Boolean = normalize(mimeType) in VIDEO_TYPES

    /** Whether Nivara will hand this item to the platform's media stack as audio. */
    fun isPlayableAudio(mimeType: String?): Boolean = normalize(mimeType) in AUDIO_TYPES

    /** Whether Nivara will show this item as text. */
    fun isReadableText(mimeType: String?): Boolean = normalize(mimeType) in TEXT_TYPES

    /** Whether Nivara will render this item as a document. */
    fun isRenderableDocument(mimeType: String?): Boolean = normalize(mimeType) == PDF_TYPE

    /**
     * Whether Nivara has a viewer for this type, whatever that viewer is.
     *
     * `false` is not an error and not a missing file: it is a file Nivara keeps, lists and describes
     * without showing. The screen says exactly that.
     */
    fun hasViewer(mimeType: String?): Boolean = when (kindOf(mimeType)) {
        VaultContentKind.Image -> isViewableImage(mimeType)
        VaultContentKind.Video -> isPlayableVideo(mimeType)
        VaultContentKind.Audio -> isPlayableAudio(mimeType)
        VaultContentKind.Document -> isReadableText(mimeType) || isRenderableDocument(mimeType)
        VaultContentKind.Other -> false
    }

    /**
     * A declared type reduced to the form the sets are written in.
     *
     * Returns `null` for a missing, blank, oversized or malformed type — the same rules the index
     * applies when it stores one, applied again here because a classification must never act on
     * something the index would not have kept.
     */
    private fun normalize(mimeType: String?): String? {
        val declared = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (declared.isEmpty()) return null
        return if (VaultItemNames.isWellFormedMimeType(declared)) declared else null
    }
}
