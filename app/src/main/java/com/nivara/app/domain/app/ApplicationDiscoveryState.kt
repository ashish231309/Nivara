package com.nivara.app.domain.app

/**
 * Whether application discovery produced an answer, separately from how many applications it found.
 *
 * An empty [Available] list is a real (if unusual) result: the platform was queried and reported no
 * launchable applications. [Unavailable] means the platform could not be queried, and a screen must
 * never present it as "no applications", because that would turn a failure into a fact.
 */
sealed interface ApplicationDiscoveryState {

    /** The platform was queried; [applications] holds what it returned, possibly nothing. */
    data class Available(val applications: List<InstalledApplication>) : ApplicationDiscoveryState

    /** The platform could not be queried. */
    data object Unavailable : ApplicationDiscoveryState
}
