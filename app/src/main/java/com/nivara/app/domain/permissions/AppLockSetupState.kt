package com.nivara.app.domain.permissions

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.fold
import com.nivara.app.domain.app.ApplicationDiscoveryState
import com.nivara.app.domain.app.InstalledApplication

/** A precondition App Lock needs before it can be built. */
enum class AppLockPrerequisite {

    /** The device's user-launchable applications, discovered without an error. */
    ApplicationDiscovery,

    /** Android's grant that lets Nivara recognise the application in the foreground. */
    UsageAccess,

    /**
     * Android's grant that lets Nivara draw its protection surface above another application.
     *
     * Detection and presentation are two different capabilities, and a device can have either
     * without the other: Usage Access says which application the user is looking at, and this one
     * says whether Nivara may cover it. Both are prerequisites, because detection that cannot
     * present anything is not protection.
     */
    Overlay,
}

/**
 * What App Lock already has and what it is still missing.
 *
 * One value carries every answer, so a screen cannot show a readiness claim that disagrees with the
 * details underneath it: [missingPrerequisites] is derived from the same three fields the details
 * are drawn from, and readiness is only ever claimed when all three are positive.
 *
 * The model covers exactly the capabilities this stage establishes. Nothing is added in advance:
 * every further entry would be a permission requested for a feature that does not exist yet.
 */
data class AppLockSetupState(
    val discovery: ApplicationDiscoveryState,
    val usageAccess: UsageAccessStatus,
    val overlay: OverlayCapability,
) {

    /** The applications that were discovered, or an empty list when discovery failed. */
    val applications: List<InstalledApplication>
        get() = (discovery as? ApplicationDiscoveryState.Available)?.applications ?: emptyList()

    /** The preconditions that are not satisfied, in a fixed order. */
    val missingPrerequisites: List<AppLockPrerequisite> = buildList {
        if (discovery !is ApplicationDiscoveryState.Available) {
            add(AppLockPrerequisite.ApplicationDiscovery)
        }
        if (usageAccess != UsageAccessStatus.Granted) {
            add(AppLockPrerequisite.UsageAccess)
        }
        if (overlay != OverlayCapability.Granted) {
            add(AppLockPrerequisite.Overlay)
        }
    }

    /** `true` when App Lock has everything this stage prepares. */
    val isReady: Boolean get() = missingPrerequisites.isEmpty()

    companion object {

        /**
         * Builds the aggregate from the three platform answers.
         *
         * A failed discovery query becomes [ApplicationDiscoveryState.Unavailable]; an empty but
         * successful one becomes [ApplicationDiscoveryState.Available] with no entries. The two are
         * never conflated. A capability that is not granted — for any reason, including one that
         * could not be read — is a missing prerequisite, so readiness is only ever claimed when
         * every answer is positive.
         */
        fun of(
            discovery: NivaraResult<List<InstalledApplication>>,
            usageAccess: UsageAccessStatus,
            overlay: OverlayCapability,
        ): AppLockSetupState = AppLockSetupState(
            // `fold` keeps the generic payload behind the result type's own helpers, so no
            // unchecked cast is written here.
            discovery = discovery.fold(
                onSuccess = { applications -> ApplicationDiscoveryState.Available(applications) },
                onFailure = { ApplicationDiscoveryState.Unavailable },
            ),
            usageAccess = usageAccess,
            overlay = overlay,
        )
    }
}
