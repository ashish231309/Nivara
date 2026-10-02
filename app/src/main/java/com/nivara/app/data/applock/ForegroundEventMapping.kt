package com.nivara.app.data.applock

import com.nivara.app.domain.applock.ForegroundApplication

/**
 * What a platform usage event says about the foreground application.
 *
 * Only two transitions matter: an activity came to the front and an activity left it. Everything
 * else — configuration changes, device state, notifications, service events — says nothing about
 * which application the user is looking at, and is ignored rather than guessed at.
 */
internal enum class ForegroundSignal {

    /** An activity came to the front. */
    Entered,

    /** An activity that was in front left it. */
    Left,

    /** Anything else: not a foreground transition, or not one this layer needs. */
    Ignored,
}

/**
 * The platform event types that mean "came to the front" and "left the front".
 *
 * Both values are stable: `ACTIVITY_RESUMED` and `ACTIVITY_PAUSED` were introduced in API 29 as the
 * names of the two events older devices report as `MOVE_TO_FOREGROUND` and `MOVE_TO_BACKGROUND`,
 * and the numbers are the same on every Android version Nivara supports. They are mirrored here as
 * plain integers so the mapping below can be tested without an Android framework, and an
 * instrumented test asserts these numbers against `UsageEvents.Event` on a real device — the mirror
 * is not taken on trust.
 */
internal const val USAGE_EVENT_ACTIVITY_RESUMED: Int = 1

/** See [USAGE_EVENT_ACTIVITY_RESUMED]. */
internal const val USAGE_EVENT_ACTIVITY_PAUSED: Int = 2

/** The signal [eventType] carries. Unknown or irrelevant types are [ForegroundSignal.Ignored]. */
internal fun foregroundSignalForEventType(eventType: Int): ForegroundSignal = when (eventType) {
    USAGE_EVENT_ACTIVITY_RESUMED -> ForegroundSignal.Entered
    USAGE_EVENT_ACTIVITY_PAUSED -> ForegroundSignal.Left
    else -> ForegroundSignal.Ignored
}

/**
 * One foreground transition, copied out of the platform's reusable event object.
 *
 * The platform hands out a single mutable event instance per query, so each transition that matters
 * is read into a value of its own before it is used.
 */
internal class ForegroundTransition(
    val timeStampMillis: Long,
    val signal: ForegroundSignal,
    val packageName: String?,
)

/**
 * Folds the transitions of one query into the application now in front.
 *
 * The transitions are applied oldest first, whatever order the platform returned them in, so the
 * newest relevant one decides the answer — which is the whole point: an application that came
 * forward last is the one the user is looking at.
 */
internal fun resolveForeground(
    current: ForegroundApplication?,
    transitions: List<ForegroundTransition>,
): ForegroundApplication? =
    transitions
        .sortedBy { transition -> transition.timeStampMillis }
        .fold(current) { foreground, transition ->
            applyForegroundSignal(foreground, transition.signal, transition.packageName)
        }

/**
 * Applies one signal to the application currently believed to be in front.
 *
 * Three details are deliberate:
 *
 * - **The newest event wins.** Signals are applied in order, and each one replaces the answer
 *   rather than comparing against it, so the last transition in a burst is the one that counts.
 * - **A pause only clears the application it names.** A background application pausing says
 *   nothing about the one in front; only the foreground application leaving clears the answer.
 * - **An unusable package name is ignored.** The platform reports events for components that have
 *   no package of their own; those carry no information and must not be mistaken for a package
 *   called `""` — nor clear a foreground application that is still there.
 */
internal fun applyForegroundSignal(
    current: ForegroundApplication?,
    signal: ForegroundSignal,
    packageName: String?,
): ForegroundApplication? = when (signal) {
    ForegroundSignal.Entered -> ForegroundApplication.of(packageName) ?: current
    ForegroundSignal.Left ->
        if (current != null && current.packageName == packageName) null else current
    ForegroundSignal.Ignored -> current
}
