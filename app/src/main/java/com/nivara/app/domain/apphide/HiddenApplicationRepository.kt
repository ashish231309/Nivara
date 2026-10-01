package com.nivara.app.domain.apphide

import com.nivara.app.core.common.NivaraResult

/**
 * The applications the user asked Nivara to keep out of sight.
 *
 * This is the **one** owner of hidden-application state. There is no second store, no cache and no
 * settings copy of it, because two answers to "which applications are hidden?" is worse than none:
 * a launcher that reads one and a screen that writes the other would disagree about what the user
 * asked for, and the disagreement would be invisible until an application appeared that the user
 * believed was hidden.
 *
 * ### What it is, and what it is not
 *
 * The stored set is configuration, not authorization: it says what to hide, and the session gate
 * says whether Nivara is currently unlocked. The two live in different layers on purpose, so a
 * change to the hidden set can never open or close a session, and an expiring session can never
 * rewrite configuration.
 *
 * Hiding is **Nivara's own preference**, and this contract is honest about that: it records what the
 * user asked for and does not change anything on the device. It does not disable an application, it
 * does not touch another application's components, and it does not remove anything from Android's
 * launcher. Android still considers every application in this set installed and launchable, and the
 * system launcher still shows it — hiding from Android is not something an unprivileged application
 * can do, and Nivara does not pretend otherwise. What the set governs is Nivara's own launcher and
 * app drawer, which consume this same repository rather than reading its storage.
 *
 * ### Adding and removing
 *
 * One application at a time, by exact package name. Searching, ordering, sections and everything
 * else a screen draws belong to the screen; nothing here is a bulk operation or a policy.
 */
interface HiddenApplicationRepository {

    /**
     * Which applications are hidden, as one of three outcomes.
     *
     * Never throws and never reports a failure the caller has to interpret: [Available] carries the
     * stored set — which may legitimately be empty — and the other two cases mean the set could not
     * be determined and nothing may be claimed. See [HiddenApplicationsRead] for why the three are
     * separate.
     */
    suspend fun hiddenApplications(): HiddenApplicationsRead

    /**
     * Hides [application].
     *
     * Idempotent: hiding an already-hidden application leaves the set unchanged and does not report
     * a failure, so a repeated tap cannot produce a duplicate entry.
     *
     * A failure means the change could not be stored, in which case the previously stored set
     * remains authoritative rather than being replaced with a partial one. The caller must not
     * report success it did not observe — the intended use is to re-read and draw what the
     * repository says, never what the tap asked for.
     */
    suspend fun hide(application: HiddenApplication): NivaraResult<Unit>

    /**
     * Stops hiding [application].
     *
     * Idempotent in the same way: removing an application that is not hidden is not an error.
     */
    suspend fun unhide(application: HiddenApplication): NivaraResult<Unit>
}
