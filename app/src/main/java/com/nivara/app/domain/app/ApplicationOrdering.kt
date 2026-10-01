package com.nivara.app.domain.app

/**
 * The order a discovered application list is shown in: the user-visible label, compared
 * case-insensitively, with the package name as the tie-breaker.
 *
 * Package names are unique on a device, so the order is total: the same set of applications always
 * comes out in the same sequence, whatever order the platform reported them in. Comparing labels
 * case-insensitively is what a person expects; comparing them by code point would put every
 * uppercase label first and make "camera" sort away from "Camera".
 *
 * This is the ordering Nivara uses wherever it shows applications, and the management screen's
 * other option is its exact reverse rather than a second rule: there is one place that decides how
 * two applications compare, and both directions come from it.
 */
val defaultApplicationOrder: Comparator<InstalledApplication> =
    compareBy({ it.label.lowercase() }, { it.packageName })

/**
 * [defaultApplicationOrder] reversed: labels compared case-insensitively from Z to A.
 *
 * Reversing the whole comparator also reverses the package-name tie-breaker, which is what keeps
 * this a *total* order of the same kind as the one it mirrors: two applications with the same label
 * still come out in a fixed sequence, and the same device always produces the same list. The
 * ordering is deliberately nothing more than a direction — no ranking, no recency, no per-device
 * heuristics, because a list that changes for reasons the user cannot see is worse than a plain
 * alphabetical one.
 */
val reverseApplicationOrder: Comparator<InstalledApplication> = defaultApplicationOrder.reversed()

/** Returns this list in [defaultApplicationOrder], leaving the receiver untouched. */
fun List<InstalledApplication>.inDefaultApplicationOrder(): List<InstalledApplication> =
    sortedWith(defaultApplicationOrder)

/** Returns this list in [reverseApplicationOrder], leaving the receiver untouched. */
fun List<InstalledApplication>.inReverseApplicationOrder(): List<InstalledApplication> =
    sortedWith(reverseApplicationOrder)
