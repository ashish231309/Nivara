package com.nivara.app.domain.credential

/**
 * The primary authentication method that protects the user's private space.
 *
 * Exactly one primary method is active at a time. That rule is enforced by the shape of the
 * layer rather than by convention: storage holds a single credential record, the store offers no
 * way to add a second one, and switching methods replaces the record instead of adding to it.
 * "A PIN *and* a password" is therefore not a state the application can be in.
 *
 * Biometric authentication is *not* a primary method. It is a secondary convenience added in a
 * later stage, and it never replaces the credential described here.
 *
 * Identifiers are persisted, so they must stay stable forever. Values are never reused.
 */
enum class PrimaryCredentialType(
    /** Stable identifier stored with the credential record. */
    val id: Int,
) {
    /** Numeric secret entered on a keypad. */
    Pin(id = 1),

    /** Free-form text secret entered on a keyboard. */
    Password(id = 2),

    /** Sequence of points connected on a 3x3 grid. */
    Pattern(id = 3),
    ;

    companion object {
        /** Resolves a persisted identifier, or `null` when it is not a known method. */
        fun fromId(id: Int): PrimaryCredentialType? = entries.firstOrNull { it.id == id }
    }
}
