package com.nivara.app.ui.applications

/**
 * How a list of applications is ordered. One value, one direction, both taken from the domain's
 * ordering.
 *
 * It lives here rather than with one screen because more than one screen offers the choice — the
 * App Lock settings list and the hidden-application list — and a second copy of "A–Z or Z–A" would
 * be a second answer to a question that has one. What it deliberately is *not* is a second
 * ordering rule: the comparison itself is `ApplicationOrdering`, and this type only names which
 * direction of it a list is drawn in.
 *
 * The two values are the whole of the choice. There is deliberately no "recently used" or
 * "recommended" order: Nivara keeps no usage history, and inventing a ranking would mean either
 * collecting one or guessing.
 */
enum class ApplicationSortOrder {

    /** Labels A–Z, case-insensitively, with the package name breaking ties. */
    NameAscending,

    /** The same comparison, reversed. */
    NameDescending,
}
