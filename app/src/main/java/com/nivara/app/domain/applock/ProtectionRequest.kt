package com.nivara.app.domain.applock

/**
 * One occasion on which a protected application needs authentication.
 *
 * ### Why a request is more than a package name
 *
 * The package name is the *identity* of what is protected — that part never changes — but it is not
 * enough to decide whether an authentication result may be used. Consider the sequence the identity
 * alone cannot tell apart:
 *
 * ```
 *   A comes forward      → request 1 for A     user starts authenticating
 *   user switches to B   → request 2 for B
 *   A's authentication succeeds
 * ```
 *
 * The success belongs to a moment that has passed: the user has moved on, and acting on it would
 * open the gate for a situation nobody asked about. [id] is what makes that decision possible — it
 * is issued once per occasion, so a result can be matched against the request it was started for
 * and discarded when that request is no longer current. A request is never reused and never
 * resurrected, so there is no per-application "unlocked until" state anywhere: only the current
 * occasion.
 *
 * ### What it deliberately does not carry
 *
 * No label, no icon, no activity name and no display position. Nothing here is derived from
 * anything a person or another application could influence, and nothing here is stored: the request
 * lives in memory for as long as the occasion does.
 */
data class ProtectionRequest(

    /** Identifies this occasion. Monotonic within the process, never written down. */
    val id: Long,

    /** The protected application this occasion is about, identified by package name. */
    val application: ProtectedApplication,
)
