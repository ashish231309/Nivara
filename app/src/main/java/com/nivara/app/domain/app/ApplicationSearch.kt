package com.nivara.app.domain.app

/**
 * Matching between a search query and an application: the label, or the package name.
 *
 * There is no search field yet, and this is not one. What is here is the rule the App Lock screen
 * will need, kept Android-free so it can be tested without a device: a query is trimmed and
 * lowercased once, then matched as a substring against the label and against the package name.
 * Nothing clever is attempted — no ranking, no fuzzy matching, no index — because a list of apps
 * on one device is small, and behaviour a user cannot predict is worse than a plain match.
 *
 * The package name is searched so that a query typed from documentation or a settings screen still
 * finds the application; it is never used as a display name.
 */
object ApplicationSearch {

    /** Trims [query] and lowercases it; the result is what [matches] expects. */
    fun normalize(query: String): String = query.trim().lowercase()

    /**
     * `true` when [application] matches [normalizedQuery], which must come from [normalize].
     *
     * An empty query matches everything, so filtering an unfiltered list is a no-op.
     */
    fun matches(application: InstalledApplication, normalizedQuery: String): Boolean =
        normalizedQuery.isEmpty() ||
            application.label.lowercase().contains(normalizedQuery) ||
            application.packageName.lowercase().contains(normalizedQuery)

    /** The applications from [applications] that match [query], in the order they were given. */
    fun filter(applications: List<InstalledApplication>, query: String): List<InstalledApplication> {
        val normalized = normalize(query)
        return applications.filter { application -> matches(application, normalized) }
    }
}
