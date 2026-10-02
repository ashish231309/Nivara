package com.nivara.app.domain.app

/**
 * One application installed on this device that the user can open.
 *
 * The package name is the identity. A label is text the user can change, two applications can
 * carry the same one, and a label is not unique on a device — so it is what a screen shows and
 * nothing else. Nothing about the application's contents, permissions, icon or data is modelled
 * here, because App Lock never needs any of it.
 *
 * Equality follows the identity rule too: two records with the same package name are the same
 * application even if their labels were resolved differently. That is what makes de-duplication
 * and list keys safe, and it is why a screen must ask [packageName] — never [label] — whenever the
 * question is "which application is this?".
 */
data class InstalledApplication(
    val packageName: String,
    val label: String,
) {
    init {
        require(packageName.isNotBlank()) { "an application is identified by its package name" }
        require(label.isNotBlank()) { "an application label is never blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is InstalledApplication && other.packageName == packageName

    override fun hashCode(): Int = packageName.hashCode()
}
