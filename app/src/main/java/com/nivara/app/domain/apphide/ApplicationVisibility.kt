package com.nivara.app.domain.apphide

/**
 * Whether an application is hidden from Nivara's own view of the device.
 *
 * Two values, and deliberately no third: "unknown" is not a visibility state, it is the absence of
 * an answer, and it is represented by the *absence* of this value — a screen that cannot read the
 * hidden set draws no state for a row rather than inventing one of these two. That is the same rule
 * the App Lock settings list follows for protection, and for the same reason: a list that guesses
 * tells the user something Nivara does not know.
 *
 * Visibility is a dimension of its own and is independent of protection. An application can be
 * hidden and unprotected, protected and visible, both, or neither; nothing in this type implies
 * anything about App Lock, and nothing in App Lock implies anything about it.
 */
enum class ApplicationVisibility {

    /** The application is in the stored hidden set. */
    Hidden,

    /** The application is not in the stored hidden set. */
    Visible,
}
