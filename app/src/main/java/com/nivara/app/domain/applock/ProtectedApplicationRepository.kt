package com.nivara.app.domain.applock

import com.nivara.app.core.common.NivaraResult

/**
 * The set of applications the user asked Nivara to protect.
 *
 * This is configuration, not authentication: the set says *what* to protect, and the session gate
 * says whether Nivara is currently unlocked. The two are kept in different layers on purpose, so
 * that a change to the protected set can never open or close a session, and an expiring session can
 * never rewrite configuration.
 *
 * A [NivaraResult.Failure] means the set could not be read. That is **not** an empty set: an
 * unreadable list of protected applications is a reason to stop making protection decisions, never
 * a reason to decide that nothing needs protecting. The implementation decides what makes a list
 * unreadable, and its contract comment states the consequence.
 *
 * Adding and removing take one application at a time. Choosing many at once, searching and sorting
 * belong to the settings screen that uses this same contract; nothing here is a bulk operation or a
 * policy.
 */
interface ProtectedApplicationRepository {

    /** Every protected application, or a failure when the stored set cannot be read. */
    suspend fun protectedApplications(): NivaraResult<Set<ProtectedApplication>>

    /**
     * Protects [application].
     *
     * Idempotent: protecting an already-protected application leaves the set unchanged and does not
     * report a failure. A failure means the change could not be stored, in which case the stored
     * set is left as it was rather than half-written.
     */
    suspend fun protect(application: ProtectedApplication): NivaraResult<Unit>

    /**
     * Stops protecting [application].
     *
     * Idempotent in the same way: removing an application that is not protected is not an error.
     */
    suspend fun unprotect(application: ProtectedApplication): NivaraResult<Unit>
}
