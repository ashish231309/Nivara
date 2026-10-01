package com.nivara.app.domain.apphide

/**
 * The outcome of asking which applications are hidden.
 *
 * Three cases, and the third one is the reason this type exists rather than a plain set:
 *
 * ```
 *   Available   the stored state was read; here is what it holds — possibly nothing
 *   Unreadable  the stored state exists but cannot be decoded, so nothing may be claimed
 *   Unavailable the stored state could not be reached at all, so nothing may be claimed
 * ```
 *
 * An unreadable or unavailable hidden set is **not** an empty one. Reading it as "nothing is
 * hidden" would make a damaged file expose every application the user asked to keep out of sight,
 * which is the single most damaging thing this feature could do, and it is why the distinction is
 * part of the contract rather than a convention its callers are trusted to follow. A consumer —
 * the management screen today, Nivara's launcher later — cannot accidentally collapse the three
 * cases into two, because the compiler makes it handle all of them.
 *
 * The two failure cases are kept apart because they are different facts: [Unreadable] is damage to
 * a file that is there, [Unavailable] is storage that could not be queried at all. Neither one is
 * repaired, overwritten or guessed at — a change made while either is showing is refused.
 *
 * This type performs no I/O and holds no cache: it is a caller's last read, and it becomes stale
 * the moment anything is written.
 */
sealed interface HiddenApplicationsRead {

    /** The stored set was read. [hidden] is exactly what it held, possibly empty. */
    data class Available(val hidden: Set<HiddenApplication>) : HiddenApplicationsRead

    /** The stored set exists but cannot be decoded. Nothing may be claimed about any application. */
    data object Unreadable : HiddenApplicationsRead

    /** The stored set could not be reached. Nothing may be claimed about any application. */
    data object Unavailable : HiddenApplicationsRead

    /**
     * Whether [packageName] is hidden, or `null` when the stored set could not be read.
     *
     * `null` is never "visible": it is the honest answer when there is no authoritative answer, and
     * a row drawn from it must claim nothing.
     */
    fun visibilityOf(packageName: String): ApplicationVisibility? = when (this) {
        Unreadable, Unavailable -> null

        is Available -> when {
            hidden.hides(packageName) -> ApplicationVisibility.Hidden
            else -> ApplicationVisibility.Visible
        }
    }

    /** How many applications the stored set holds, or `null` when it could not be read. */
    val hiddenCount: Int?
        get() = (this as? Available)?.hidden?.size

    /** `true` only for [Available] — a successful read, whether or not it found anything. */
    val isAvailable: Boolean
        get() = this is Available
}
