package com.nivara.app.domain.applock

/**
 * An application the user asked Nivara to protect.
 *
 * The package name is the whole model, and that is the point. A label can be changed by the user
 * or by the application itself, two applications can share one, a launcher activity is renamed
 * when an application is refactored and an icon is just a drawable — none of those is an identity.
 * The package name is what the platform reports for the application in the foreground, so it is
 * the only thing Nivara can match against, and it is the only thing Nivara stores.
 *
 * Two records with the same package name are the same protected application, which is what makes
 * the set of them safe to de-duplicate, store and compare.
 *
 * Nothing about the application itself is kept here: no label, no icon, no components, no
 * authentication material and no per-application state. What the user granted is protection, not a
 * copy of the application.
 */
data class ProtectedApplication(val packageName: String) {

    init {
        require(PackageNames.isUsable(packageName)) {
            "a protected application is identified by a usable package name"
        }
    }
}

/**
 * Whether these protected applications cover [packageName].
 *
 * The decision layer asks the question this way — with a name the platform reported — rather than
 * by carrying [ProtectedApplication] instances around, so there is exactly one comparison in the
 * application: package name to package name.
 */
fun Collection<ProtectedApplication>.protects(packageName: String): Boolean =
    any { application -> application.packageName == packageName }
