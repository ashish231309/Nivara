package com.nivara.app.domain.apphide

/**
 * Typed failures produced by the hidden-application layer.
 *
 * These describe what a **change** could not do. A read does not fail: it answers with one of
 * [HiddenApplicationsRead]'s three cases, because "Nivara cannot say which applications are hidden"
 * is an answer a caller must be able to hold and act on, not an exception to catch.
 *
 * Each case exists because a caller behaves differently, or because a reader of a report must be
 * able to tell two situations apart. Messages are fixed, non-secret strings: they carry no package
 * names, no paths and no platform detail, so they stay safe if they ever reach a crash report. A
 * diagnostic cause, where the platform offers one, is attached as `cause` and never shown to the
 * user.
 */
sealed class HiddenApplicationFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The stored hidden set could not be read, so the change was not applied.
     *
     * A change is a read followed by a write. When the read half fails there is nothing to modify,
     * and writing anyway would replace a configuration whose contents are unknown — possibly with a
     * set that exposes applications the user hid. So the change is refused and the file is left
     * exactly as it was.
     */
    data object HiddenApplicationsUnreadable :
        HiddenApplicationFailure("the hidden application set is not readable")

    /**
     * The stored hidden set could not be reached at all, so the change was not applied.
     *
     * Storage that cannot be queried is not storage that is empty; the same consequence as above
     * follows, and the two cases are kept apart so a report can say which happened.
     */
    data object HiddenApplicationsUnavailable :
        HiddenApplicationFailure("the hidden application set is unavailable")

    /**
     * The change could not be stored.
     *
     * The previously stored set remains authoritative: nothing is half-written, and a caller must
     * not tell the user that an application is hidden or visible when this is the outcome.
     */
    data object HiddenApplicationsUnwritable :
        HiddenApplicationFailure("the hidden application set could not be stored")
}
