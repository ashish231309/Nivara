package com.nivara.app.domain.applock

/**
 * The package names an application can have, validated in one place.
 *
 * Android package names are dot-separated segments of letters, digits and underscores. The rule
 * here is deliberately a little looser than the platform's own — a name this layer rejects is a
 * name it can never protect or recognise — but it still refuses anything that could not be a
 * package name at all: blanks, whitespace, path separators and unbounded lengths.
 *
 * Both sides of App Lock use it. A [ProtectedApplication] only exists if the user chose a usable
 * name, and a [ForegroundApplication] is only believed when the platform reports one, so an
 * unexpected event cannot inject a value that is not an application.
 */
internal object PackageNames {

    /** Longest package name the platform allows. */
    const val MAXIMUM_LENGTH: Int = 255

    /** `true` when [packageName] could name an application on this platform. */
    fun isUsable(packageName: String): Boolean =
        packageName.isNotEmpty() &&
            packageName.length <= MAXIMUM_LENGTH &&
            packageName.any { character -> character != '.' } &&
            packageName.all { character -> character.isUsablePackageCharacter() }

    private fun Char.isUsablePackageCharacter(): Boolean =
        this == '.' || this == '_' || this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
