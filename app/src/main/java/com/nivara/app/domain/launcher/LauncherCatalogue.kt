package com.nivara.app.domain.launcher

import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.app.inDefaultApplicationOrder
import com.nivara.app.domain.apphide.ApplicationVisibility
import com.nivara.app.domain.apphide.HiddenApplicationsRead

/**
 * The applications a launcher may draw, worked out from what was discovered and what is hidden.
 *
 * ```
 *   ApplicationRepository        what the device can launch
 *   HiddenApplicationRepository  what the user asked to keep out of sight
 *                ↘              ↙
 *              launcherCatalogue(...)
 * ```
 *
 * This is the whole of Nivara's app-drawer hiding rule, and it is a pure function on purpose: it
 * takes the two repository answers as arguments, returns a value, touches no platform API, writes
 * nothing and cannot fail. That makes the security-relevant decision — *does this application get
 * drawn?* — testable without a device, and it keeps the launcher's view model from inventing a
 * second answer to "what is hidden?".
 *
 * ### The fail-closed rule
 *
 * [HiddenApplicationsRead.Unreadable] and [HiddenApplicationsRead.Unavailable] produce
 * [LauncherCatalogue.HiddenStateUnreadable] and [LauncherCatalogue.HiddenStateUnavailable] — never
 * an empty hidden set. A launcher that treated a damaged record as "nothing is hidden" would show
 * every application the user had hidden, which is precisely the outcome hiding exists to prevent,
 * and it would do so at the moment the user is most likely to be looking: the home screen.
 *
 * The two failures are separate cases rather than one, because they are different facts and the
 * screen says which happened.
 *
 * ### What the reveal changes, and what it does not
 *
 * [revealHidden] is *presentation state*: it says that an authenticated user has asked to see the
 * hidden applications for the length of their session. It does not change what is hidden — the
 * repository is not called, nothing is written, and an application that is revealed here is still
 * hidden the moment the session ends. [LauncherCatalogue.Loaded.hidden] always reports the real set,
 * whether or not it is currently being drawn.
 */
sealed interface LauncherCatalogue {

    /**
     * The hidden set was read, so the launcher has a trustworthy answer.
     *
     * @param entries exactly what may be drawn, in the domain's application order: the discovered
     *   applications minus the hidden ones, or all of them when a reveal is active.
     * @param hidden the discovered applications that are hidden, in the same order. Reported in both
     *   directions — it is what the launcher counts when it says how many applications it is
     *   withholding, and what it compares against when a reveal ends.
     * @param revealed whether the entries include hidden applications because a reveal is active.
     */
    data class Loaded(
        val entries: List<InstalledApplication>,
        val hidden: List<InstalledApplication>,
        val revealed: Boolean,
    ) : LauncherCatalogue {

        /** How many discovered applications are hidden. */
        val hiddenCount: Int get() = hidden.size

        /** How many of them the launcher is currently not drawing. */
        val withheldCount: Int get() = if (revealed) 0 else hidden.size
    }

    /** The stored hidden set exists but cannot be decoded, so nothing may be drawn. */
    data object HiddenStateUnreadable : LauncherCatalogue

    /** The stored hidden set could not be reached, so nothing may be drawn. */
    data object HiddenStateUnavailable : LauncherCatalogue
}

/**
 * Works out which discovered applications may be drawn.
 *
 * Hidden applications are omitted by exact package-name comparison, which is the only identity the
 * stored set has. A stored name that is not in [discovered] simply changes nothing: an application
 * that is uninstalled, or that discovery did not report this time, neither appears in the drawer nor
 * causes an entry to be invented, and nothing is written anywhere — so a discovery gap can never
 * unhide anything.
 *
 * The returned entries preserve nothing about the order [discovered] arrived in: they are put in the
 * domain's application order, which is the same total order the App Lock screens use, so an
 * unchanged device always produces the same drawer.
 */
fun launcherCatalogue(
    discovered: List<InstalledApplication>,
    hidden: HiddenApplicationsRead,
    revealHidden: Boolean,
): LauncherCatalogue = when (hidden) {
    HiddenApplicationsRead.Unreadable -> LauncherCatalogue.HiddenStateUnreadable

    HiddenApplicationsRead.Unavailable -> LauncherCatalogue.HiddenStateUnavailable

    is HiddenApplicationsRead.Available -> {
        val hiddenApplications = discovered.filter { application ->
            hidden.visibilityOf(application.packageName) == ApplicationVisibility.Hidden
        }
        val entries = when {
            revealHidden -> discovered
            else -> discovered.filterNot { application -> application in hiddenApplications }
        }
        LauncherCatalogue.Loaded(
            entries = entries.inDefaultApplicationOrder(),
            hidden = hiddenApplications.inDefaultApplicationOrder(),
            revealed = revealHidden,
        )
    }
}
