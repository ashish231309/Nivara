package com.nivara.app.domain.credential

/**
 * A credential as the user entered it, before it is validated or derived from.
 *
 * Two decisions are deliberate here:
 *
 * - **Buffers, not `String`s.** A `String` is immutable, survives in the heap until the
 *   collector happens to reclaim it, and cannot be overwritten. PIN digits, password characters
 *   and pattern points are therefore held in arrays the owner can clear.
 * - **Ownership transfer.** Every entry point of [CredentialManager] takes ownership of the
 *   inputs it is given and calls [clear] on them before returning, whether the operation
 *   succeeded, failed or was cancelled. Callers must not use an input after handing it over.
 *
 * The sub-types keep the method and its representation together, so a pattern can never be
 * passed where a password is expected.
 */
sealed interface CredentialInput {

    /** The method this input belongs to. */
    val type: PrimaryCredentialType

    /** Overwrites the buffer. Safe to call more than once. */
    fun clear()

    /** Digits entered on a keypad. Only `0`–`9` are accepted by the policy. */
    class Pin(val digits: CharArray) : CredentialInput {

        override val type: PrimaryCredentialType get() = PrimaryCredentialType.Pin

        override fun clear() {
            digits.fill(ZERO_CHAR)
        }

        override fun toString(): String = "CredentialInput.Pin(length=${digits.size}, value=REDACTED)"
    }

    /** Characters entered on a keyboard. */
    class Password(val characters: CharArray) : CredentialInput {

        override val type: PrimaryCredentialType get() = PrimaryCredentialType.Password

        override fun clear() {
            characters.fill(ZERO_CHAR)
        }

        override fun toString(): String = "CredentialInput.Password(length=${characters.size}, value=REDACTED)"
    }

    /**
     * Points touched while drawing on the 3x3 grid, in the order they were touched.
     *
     * This is the raw gesture. It is converted to the canonical form by [PatternCanonicalizer]
     * before it is validated or derived from, so the same drawing always produces the same
     * protection value regardless of how it was traced.
     */
    class Pattern(val points: IntArray) : CredentialInput {

        override val type: PrimaryCredentialType get() = PrimaryCredentialType.Pattern

        override fun clear() {
            points.fill(NO_POINT)
        }

        override fun toString(): String = "CredentialInput.Pattern(points=${points.size}, value=REDACTED)"
    }

    private companion object {
        const val ZERO_CHAR: Char = '\u0000'
        const val NO_POINT: Int = -1
    }
}
