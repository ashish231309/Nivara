package com.nivara.app.data.vault.viewer

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultContentException
import com.nivara.app.domain.vault.VaultContentFailure
import java.io.IOException

/**
 * Why a viewer could not show an item.
 *
 * Two families, kept apart on purpose. [Content] is everything that went wrong *before* any byte was
 * decrypted — a vault that cannot be opened, an object that is not there, storage that refused, bytes
 * that did not authenticate, a gate that closed — and it carries the vault domain's own typed failure,
 * because those are facts the vault already knows how to describe. [DecodeFailed] and [Unsupported]
 * are about what happened *after* the plaintext arrived: the platform's decoder refusing a file it
 * cannot parse, or a type this stage has no viewer for.
 *
 * The distinction is the one the stage requires: an unsupported format is not corruption, and a file
 * whose bytes authenticated but whose codec the device lacks is not a damaged vault.
 */
internal sealed interface VaultViewerFailure {

    /** The content could not be read; the vault's own typed reason travels with it. */
    data class Content(val failure: VaultContentFailure) : VaultViewerFailure

    /** The bytes were read and the platform's decoder refused them. Never presented as damage. */
    data object DecodeFailed : VaultViewerFailure

    /** This stage has no viewer for the item's type. The item is listed and described, not shown. */
    data object Unsupported : VaultViewerFailure

    /** The viewer was closed while the item was still opening. Nothing is left behind. */
    data object Cancelled : VaultViewerFailure
}

/** A typed viewer failure travelling through a [NivaraResult]. */
internal class VaultViewerException(val failure: VaultViewerFailure) :
    Exception(failure.toString(), null)

/** The viewer failure behind a failed result, or `null` when the result succeeded. */
internal fun <T> NivaraResult<T>.viewerFailure(): VaultViewerFailure? = when (this) {
    is NivaraResult.Success -> null
    is NivaraResult.Failure -> when (val error = error) {
        is VaultViewerException -> error.failure
        is VaultContentException -> VaultViewerFailure.Content(error.failure)
        is VaultContentFailure -> VaultViewerFailure.Content(error)
        null -> VaultViewerFailure.Content(VaultContentFailure.Unreadable)
        else -> VaultViewerFailure.Content(error.asViewerContentFailure())
    }
}

/** The content failure behind an exception a platform decoder or the vault reader threw. */
internal fun Exception?.asViewerContentFailure(): VaultContentFailure = when (this) {
    null -> VaultContentFailure.Unreadable
    is VaultContentFailure -> this
    is VaultContentException -> failure
    is VaultViewerException -> (failure as? VaultViewerFailure.Content)?.failure
        ?: VaultContentFailure.Unreadable

    // A platform decoder reads through the vault's own input adapter, which can only tell it that the
    // object could not be read; the typed reason is kept as the cause, so bytes that failed to
    // authenticate are still reported as corruption rather than as a storage problem.
    is IOException -> (cause as? VaultContentException)?.failure ?: VaultContentFailure.Unreadable
    else -> VaultContentFailure.Unreadable
}

/** A failed viewer result, for the places that report one. */
internal fun viewerFailure(failure: VaultViewerFailure): NivaraResult<Nothing> =
    NivaraResult.Failure(VaultViewerException(failure))

/** A failed viewer result carrying a vault content failure. */
internal fun contentFailure(failure: VaultContentFailure): NivaraResult<Nothing> =
    NivaraResult.Failure(VaultViewerException(VaultViewerFailure.Content(failure)))
