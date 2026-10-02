package com.nivara.app.ui.applock.management

import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.applock.ProtectionRunState
import com.nivara.app.ui.components.NivaraMessage

/**
 * State of the App Lock management screen.
 *
 * The same three-case shape as the rest of Nivara — loading, ready, error — with one addition that
 * matters more here than anywhere else: the ready state carries *separate* answers for the things a
 * user needs to tell apart. There is no single `isReady` flag, because collapsing them would mean
 * telling the user that everything is fine when a capability is missing, or that nothing is
 * protected when the configuration could not be read.
 *
 * The list in [Ready.rows] is a rendering of what the repositories said at the last read. It is not
 * a source of truth: every change goes to the repository, and every change is followed by a fresh
 * read. Nothing here is persisted, and nothing here survives the process.
 */
sealed interface AppLockManagementUiState {

    /** The first result has not arrived yet. */
    data object Loading : AppLockManagementUiState

    /** The screen has data to show. */
    data class Ready(
        /** The rows to draw, already filtered by [section] and [query] and ordered by [sort]. */
        val rows: List<ManagedApplication>,

        /** Which applications the list is showing. */
        val section: ApplicationSection,

        /** Ordering of the rows, taken from the domain's application ordering. */
        val sort: ApplicationSortOrder,

        /** The current search text. Empty means "no filter". */
        val query: String,

        /**
         * The capabilities App Lock is missing, in the aggregate's own order.
         *
         * Empty means every capability is in place. A prerequisite that could not be *read* appears
         * here too: it is not satisfied, and saying it is would be a claim Nivara cannot make.
         */
        val missingPrerequisites: List<AppLockPrerequisite>,

        /** What the component that owns protection currently says, if it was reachable. */
        val runState: ProtectionRunState,

        /**
         * `true` when the stored protected set could not be read.
         *
         * The rows are then drawn without any protection claim at all — neither "protected" nor
         * "not protected" — because an unreadable configuration is not an empty one.
         */
        val storedSetUnreadable: Boolean,

        /** Whether Nivara currently has a valid session. Changes are refused while this is `false`. */
        val sessionAuthenticated: Boolean,

        /** How many applications were discovered in total, before filtering. */
        val discoveredCount: Int,

        /** How many of the discovered applications are protected. */
        val protectedCount: Int,

        /**
         * How many stored protected applications were not discovered.
         *
         * A stored name that is not installed right now is kept, not pruned: the user asked for it,
         * and an uninstalled application can come back. The number is surfaced so the list does not
         * silently disagree with the stored set.
         */
        val protectedNotInstalledCount: Int,

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
        val emptiness: AppLockListEmptiness? = null,
    ) : AppLockManagementUiState {

        /** `true` when every capability App Lock needs is in place. */
        val capabilitiesReady: Boolean get() = missingPrerequisites.isEmpty()
    }

    /** The screen could not load at all: discovery failed before anything was shown. */
    data object Error : AppLockManagementUiState
}

/**
 * Why the list has nothing to draw.
 *
 * The four cases are different situations with different remedies, so the screen never has to
 * guess one from the others: a device that shows nothing to launch, a search that matches nothing,
 * a protected set that is legitimately empty, and a protected set that could not be read. Deciding
 * this here rather than in a composable keeps the judgement testable without a device.
 */
enum class AppLockListEmptiness {

    /** The device reported no launchable applications at all. */
    DeviceHasNoApplications,

    /** The search query matched nothing. */
    NoSearchResults,

    /** The protected section is empty because nothing is protected yet. */
    NothingProtected,

    /** The stored protected set could not be read, so it cannot be listed. */
    ProtectedSetUnreadable,
}
