package com.nivara.app.domain.applock

import com.nivara.app.domain.app.PackageNames

/**
 * The application the user is currently looking at, as the platform reported it.
 *
 * A foreground application is not a discovered application: it can be the launcher, a system
 * component, a permission screen or a package that was installed a moment ago and never made it
 * into a discovery list. It is therefore modelled as a package name and nothing else — no label, no
 * icon, nothing to resolve — because the only question App Lock asks about it is whether the name
 * is in the protected set.
 *
 * [of] is how values arrive from the platform. A platform event that carries no usable package name
 * describes nothing to protect, so it becomes `null` rather than a value that would later have to
 * be special-cased.
 */
data class ForegroundApplication(val packageName: String) {

    init {
        require(PackageNames.isUsable(packageName)) {
            "a foreground application is identified by a usable package name"
        }
    }

    companion object {

        /**
         * The foreground application for a package name reported by the platform, or `null` when
         * the name could not be one.
         *
         * A missing or unusable name is not an application: the platform reports events for
         * components that have no package of their own, and those are ignored rather than treated
         * as a package called `""`.
         */
        fun of(packageName: String?): ForegroundApplication? =
            if (packageName != null && PackageNames.isUsable(packageName)) {
                ForegroundApplication(packageName)
            } else {
                null
            }
    }
}
