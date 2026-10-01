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
 * This is the only ordering the foundation needs. The App Lock screen's sorting options belong to
 * the stage that builds that screen, on top of the same identity rule.
 */
val defaultApplicationOrder: Comparator<InstalledApplication> =
    compareBy({ it.label.lowercase() }, { it.packageName })

/** Returns this list in [defaultApplicationOrder], leaving the receiver untouched. */
fun List<InstalledApplication>.inDefaultApplicationOrder(): List<InstalledApplication> =
    sortedWith(defaultApplicationOrder)
