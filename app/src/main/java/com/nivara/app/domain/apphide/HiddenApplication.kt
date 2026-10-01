package com.nivara.app.domain.apphide

import com.nivara.app.domain.app.PackageNames

/**
 * An application the user asked Nivara to keep out of sight.
 *
 * The package name is the whole model, and that is the point. A label can be changed by the user or
 * by the application itself, two applications can share one, a launcher activity is renamed when an
 * application is refactored and an icon is just a drawable — none of those is an identity. The
 * package name is what the platform reports for an installed application, so it is the only thing
 * Nivara can match against, and it is the only thing Nivara stores.
 *
 * Two records with the same package name are the same hidden application, which is what makes the
 * set of them safe to de-duplicate, store and compare — and what makes a repeated hide a no-op
 * rather than a duplicate entry.
 *
 * Nothing about the application itself is kept here: no label, no icon, no components, no
 * authentication material and no per-application state. What the user asked for is visibility
 * inside Nivara, not a copy of the application.
 *
 * ### What "hidden" means, and what it does not
 *
 * A [HiddenApplication] is a stored preference. It does not disable anything, does not change
 * another application's components and does not remove the application from Android's own launcher:
 * Android still reports the application as installed and launchable, and any launcher — including
 * the system one — still shows it. What the preference will govern is Nivara's own launcher, which
 * is a later stage's work, and until that exists the stored set is the whole of the effect. Nothing
 * here hides anything from Android, from Settings, from a package manager or from another tool on
 * the device, and no code in this feature claims otherwise.
 */
data class HiddenApplication(val packageName: String) {

    init {
        require(PackageNames.isUsable(packageName)) {
            "a hidden application is identified by a usable package name"
        }
    }
}

/**
 * Whether this set of hidden applications covers [packageName].
 *
 * The question is asked with a name the platform reported rather than by carrying
 * [HiddenApplication] instances around, so there is exactly one comparison in the application:
 * package name to package name, exactly and case-sensitively.
 */
fun Collection<HiddenApplication>.hides(packageName: String): Boolean =
    any { application -> application.packageName == packageName }
