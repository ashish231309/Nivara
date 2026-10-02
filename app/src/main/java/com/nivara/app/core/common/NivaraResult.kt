package com.nivara.app.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of an operation that can fail.
 *
 * Nivara prefers this small, explicit type over exceptions crossing layer boundaries: callers
 * are forced to handle the failure branch, and the UI can show a controlled error state
 * instead of crashing. A [Failure] keeps the original [Exception] for diagnostics only — it
 * must never be rendered to the user or written to a log.
 *
 * The value is read through [fold] or [valueOrNull] rather than by pattern matching, because
 * generic payloads are erased at runtime.
 */
sealed interface NivaraResult<out T> {

    /** The operation completed and produced [value]. */
    data class Success<out T>(val value: T) : NivaraResult<T>

    /** The operation failed. [error] is optional and intended for diagnostics. */
    data class Failure(val error: Exception? = null) : NivaraResult<Nothing>
}

/** `true` when this result carries a value. */
val NivaraResult<*>.isSuccess: Boolean
    get() = this is NivaraResult.Success<*>

/** `true` when this result reports a failure. */
val NivaraResult<*>.isFailure: Boolean
    get() = this is NivaraResult.Failure

/** The value of a successful result, or `null` when the operation failed. */
@Suppress("UNCHECKED_CAST")
fun <T> NivaraResult<T>.valueOrNull(): T? = when (this) {
    is NivaraResult.Success<*> -> value as T?
    is NivaraResult.Failure -> null
}

/**
 * Runs [onSuccess] with the produced value, or [onFailure] with the failure.
 *
 * The two branches are the only cases the type allows, and the compiler enforces that both
 * are handled.
 */
@Suppress("UNCHECKED_CAST")
inline fun <T, R> NivaraResult<T>.fold(
    onSuccess: (value: T) -> R,
    onFailure: (failure: NivaraResult.Failure) -> R,
): R = when (this) {
    is NivaraResult.Success<*> -> onSuccess(value as T)
    is NivaraResult.Failure -> onFailure(this)
}

/**
 * Runs [block] and translates the outcome into a [NivaraResult].
 *
 * Coroutine cancellation is rethrown instead of being reported as a failure: swallowing
 * [CancellationException] would break structured concurrency and keep abandoned work alive.
 */
inline fun <T> nivaraRunCatching(block: () -> T): NivaraResult<T> =
    try {
        NivaraResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        NivaraResult.Failure(error)
    }
