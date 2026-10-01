package com.nivara.app.domain.app

/**
 * The package names an application can have, validated in one place.
 *
 * Android package names are dot-separated segments of letters, digits and underscores. The rule
 * here is deliberately a little looser than the platform's own — a name this layer rejects is a
 * name it can never recognise — but it still refuses anything that could not be a package name at
 * all: blanks, whitespace, path separators and unbounded lengths.
 *
 * It lives with the application identity model because more than one feature uses it and none of
 * them owns it: [InstalledApplication]'s siblings in this package, App Lock's protected set and
 * hidden-app set all accept only names this rule allows, so an unexpected value from a platform
 * event or from a screen can never be mistaken for an application. The rule is stated once so that
 * two features cannot drift into disagreeing about what an application is.
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
