package com.nivara.app.ui.launcher

import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.components.NivaraMessage

/**
 * State of Nivara's own launcher.
 *
 * The states are deliberately more numerous than a list and a flag. A launcher that can only say
 * "here are some applications" or "here is nothing" cannot express the situation that matters most:
 * *Nivara does not know which applications are hidden, and therefore will not draw any of them.*
 * That situation is [HiddenStateUnreadable] and [HiddenStateUnavailable], and it is a whole state
 * rather than an empty list, because an empty list would be a claim Nivara cannot make and a full
 * list would be a disclosure it must not make.
 *
 * ```text
 *  Loading                     the first read has not finished
 *  DiscoveryUnavailable        the device's applications could not be listed  → retry
 *  HiddenStateUnreadable       the stored hidden set cannot be decoded        → nothing is drawn
 *  HiddenStateUnavailable      the stored hidden set could not be reached     → nothing is drawn
 *  Ready                       a drawn list, with everything it needs to explain itself
 * ```
 *
 * Nothing here is persisted. [Ready.revealed] in particular is presentation state that lives exactly
 * as long as this state object does — it is not written anywhere, it is not remembered across
 * process death, and the view model drops it the moment the session gate closes.
 */
sealed interface LauncherUiState {

    /** The first read has not produced an answer yet. */
    data object Loading : LauncherUiState

    /**
     * The device's applications could not be listed.
     *
     * Nothing is drawn. A discovery failure is not an empty device: Android's application list
     * cannot legitimately be empty on a running system, so treating this as "no applications" would
     * hide a real problem while looking like an innocent one.
     */
    data object DiscoveryUnavailable : LauncherUiState

    /**
     * The stored hidden set exists but cannot be decoded, so no application is drawn.
     *
     * This is the fail-closed state the feature exists for. Drawing the catalogue here would put
     * every application the user hid back on the home screen at the worst possible moment.
     */
    data object HiddenStateUnreadable : LauncherUiState

    /** The stored hidden set could not be reached, so no application is drawn. */
    data object HiddenStateUnavailable : LauncherUiState

    /** The launcher has a trustworthy list of what may be drawn. */
    data class Ready(
        /** The rows to draw: what may be shown now, filtered by [section] and [query], ordered. */
        val entries: List<InstalledApplication>,

        /** Which applications the drawer is showing. */
        val section: LauncherSection,

        /** Ordering of the rows, from the domain's application ordering. */
        val sort: ApplicationSortOrder,

        /** The current drawer search text. Empty means "no filter". */
        val query: String,

        /**
         * Whether hidden applications are currently being shown because an authenticated user asked
         * for them.
         *
         * Presentation only: it never changes what is stored, it ends with the session, and it is
         * never written anywhere.
         */
        val revealed: Boolean,

        /** Whether Nivara currently holds a valid session. */
        val sessionAuthenticated: Boolean,

        /** How many applications discovery returned, before hiding and filtering. */
        val discoveredCount: Int,

        /** How many of them are configured as hidden. */
        val hiddenCount: Int,

        /** How many of them are hidden *and* being withheld from the drawer right now. */
        val withheldCount: Int,

        /** `true` while an application is being started, or a reveal is being prepared. */
        val busy: Boolean = false,

        /**
         * `true` when the user asked to see hidden applications and has no valid session.
         *
         * The screen answers this by sending the user to the existing credential screen; it never
         * authenticates, and it never reveals anything by itself.
         */
        val unlockRequired: Boolean = false,

        /** Why the last action failed, when it did. Generic copy only. */
        val failure: NivaraMessage? = null,

        /** A short confirmation, when there is one to give. */
        val noticeRes: Int? = null,

        /** Why [entries] is empty, or `null` when there is something to draw. */
        val emptiness: LauncherListEmptiness? = null,
    ) : LauncherUiState {

        /** Whether a reveal can be offered at all: there is something to reveal. */
        val canReveal: Boolean get() = !revealed && hiddenCount > 0
    }
}

/** Which applications the drawer is showing. */
enum class LauncherSection {

    /** Everything Nivara may draw right now. */
    All,

    /** Only the hidden applications, and only while a reveal is active. */
    Hidden,
}

/**
 * Why the drawer has nothing to draw.
 *
 * These look identical once rendered as an empty grid, and they mean completely different
 * things — so they are separate names, decided in the view model, and the screen picks a sentence
 * for each instead of guessing.
 */
enum class LauncherListEmptiness {

    /** The device reported no launchable applications at all. */
    DeviceHasNoApplications,

    /** Every discovered application is hidden, and no reveal is active. */
    AllApplicationsHidden,

    /** The search query matched nothing. */
    NoSearchResults,

    /** The hidden section is being shown and there is nothing hidden. */
    NothingHidden,
}
