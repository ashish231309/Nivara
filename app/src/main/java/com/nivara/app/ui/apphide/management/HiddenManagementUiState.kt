package com.nivara.app.ui.apphide.management

import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.components.NivaraMessage

/**
 * State of the hidden-application management screen.
 *
 * The same three-case shape as the rest of Nivara — loading, ready, error — with the same refusal to
 * collapse separate answers into one flag. In particular there is no `isReady` and no
 * `hiddenStateReadable`: the ready state carries what the stored set actually answered, and one of
 * those answers is "I could not tell you", which is not the same as "nothing is hidden" and must
 * never be drawn as such.
 *
 * The list in [Ready.rows] is a rendering of what the repositories said at the last read. It is not
 * a source of truth: every change goes to the repository, and every change is followed by a fresh
 * read. Nothing here is persisted, and nothing here survives the process.
 */
sealed interface HiddenManagementUiState {

    /** The first result has not arrived yet. */
    data object Loading : HiddenManagementUiState

    /** The screen could not load at all: discovery failed before anything was shown. */
    data object Error : HiddenManagementUiState

    /** The screen has data to show. */
    data class Ready(
        /** The rows to draw, already filtered by [section] and [query] and ordered by [sort]. */
        val rows: List<ManagedHiddenApplication>,

        /** Which applications the list is showing. */
        val section: HiddenSection,

        /** Ordering of the rows, taken from the domain's application ordering. */
        val sort: ApplicationSortOrder,

        /** The current search text. Empty means "no filter". */
        val query: String,

        /**
         * What the stored hidden set answered, as counts rather than as a set.
         *
         * [HiddenStateAvailability.Unreadable] and [HiddenStateAvailability.Unavailable] are not
         * empty answers: the screen states which one applies, claims nothing about any application,
         * and offers no change while it holds.
         */
        val hiddenState: HiddenStateAvailability,

        /** Whether Nivara currently has a valid session. Changes are refused while this is `false`. */
        val sessionAuthenticated: Boolean,

        /** How many applications were discovered in total, before filtering. */
        val discoveredCount: Int,

        /** `true` while a change is being stored. Controls are disabled meanwhile. */
        val busy: Boolean = false,

        /** Why the last action failed, when it did. Generic copy only. */
        val failure: NivaraMessage? = null,

        /** A short confirmation for the last change, when it succeeded. */
        val noticeRes: Int? = null,

        /**
         * Why [rows] is empty, or `null` when there is something to draw.
         *
         * Computed with the rows, so the list and its explanation can never disagree.
         */
        val emptiness: HiddenListEmptiness? = null,
    ) : HiddenManagementUiState {

        /**
         * Whether a hide or unhide may be attempted at all.
         *
         * Three facts, and all three are required: the stored set must have been read (otherwise the
         * change cannot be reasoned about), the gate must be open, and no other change may be in
         * flight.
         */
        val canChange: Boolean
            get() = sessionAuthenticated && hiddenState is HiddenStateAvailability.Available && !busy
    }
}

/**
 * What the stored hidden set said, as the screen needs it.
 *
 * Counts rather than the set itself: the rows already carry the per-application answer, and the
 * screen's other need is a number to state. The two failure cases stay separate because they are
 * different facts — damage to a file that is there, and storage that could not be reached — and the
 * screen says which one happened instead of showing a count it does not have.
 */
sealed interface HiddenStateAvailability {

    /**
     * The stored set was read.
     *
     * @param hiddenCount how many applications are hidden, whether or not they are installed.
     * @param notInstalledCount how many stored names were not discovered on this device.
     */
    data class Available(
        val hiddenCount: Int,
        val notInstalledCount: Int,
    ) : HiddenStateAvailability

    /** The stored set exists but cannot be decoded. */
    data object Unreadable : HiddenStateAvailability

    /** The stored set could not be reached at all. */
    data object Unavailable : HiddenStateAvailability
}

/**
 * Why the list has nothing to draw.
 *
 * Five cases, each a different situation with a different sentence, so the screen never has to guess
 * one from the others — and, in particular, never says "nothing is hidden" when the truth is that
 * Nivara could not read which applications are hidden. Deciding this here rather than in a
 * composable keeps the judgement testable without a device.
 */
enum class HiddenListEmptiness {

    /** The device reported no launchable applications at all. */
    DeviceHasNoApplications,

    /** The search query matched nothing. */
    NoSearchResults,

    /** The hidden section is empty because nothing has been hidden yet. */
    NothingHidden,

    /** The stored hidden set cannot be decoded, so it cannot be listed. */
    HiddenStateUnreadable,

    /** The stored hidden set could not be reached, so it cannot be listed. */
    HiddenStateUnavailable,
}
