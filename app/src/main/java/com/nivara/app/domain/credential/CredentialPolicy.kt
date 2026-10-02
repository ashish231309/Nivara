package com.nivara.app.domain.credential

/**
 * The rules a credential must satisfy before it is enrolled.
 *
 * The rules are deliberately modest. Length is the only requirement that reliably buys entropy,
 * so it is enforced for every method and everything else is kept to obvious mistakes: a PIN of
 * the same digit repeated, a straight run of digits, a handful of the most-guessed secrets, and
 * a pattern that is a full sweep of the grid. Character-class rules, forced symbols and rotating
 * expirations are not applied, because they push people towards worse secrets and towards
 * writing them down.
 *
 * All checks are deterministic and local: no list is downloaded, nothing is measured against a
 * network service, and no credential is transmitted anywhere. The lists are short on purpose —
 * they are a nudge, not a guarantee, and the real cost against guessing is the key-derivation
 * work factor plus attempt throttling.
 *
 * The rules live here, in the domain, so validation can be tested and reasoned about without
 * Android, and so the same policy is applied wherever a credential is enrolled, verified or
 * changed.
 */
data class CredentialPolicy(
    val minimumPinLength: Int = DEFAULT_MINIMUM_PIN_LENGTH,
    val maximumPinLength: Int = DEFAULT_MAXIMUM_PIN_LENGTH,
    val minimumPasswordLength: Int = DEFAULT_MINIMUM_PASSWORD_LENGTH,
    val maximumPasswordLength: Int = DEFAULT_MAXIMUM_PASSWORD_LENGTH,
    val minimumPatternPoints: Int = DEFAULT_MINIMUM_PATTERN_POINTS,
) {

    init {
        require(minimumPinLength >= 1 && maximumPinLength >= minimumPinLength) {
            "invalid PIN length bounds"
        }
        require(minimumPasswordLength >= 1 && maximumPasswordLength >= minimumPasswordLength) {
            "invalid password length bounds"
        }
        require(minimumPatternPoints >= MINIMUM_DRAWABLE_POINTS) {
            "a pattern needs at least $MINIMUM_DRAWABLE_POINTS points to be drawable"
        }
    }

    /**
     * Validates [credential] for [type].
     *
     * Returns the failure to report, or `null` when the credential is acceptable. For patterns,
     * [credential] must already be in canonical form — see [PatternCanonicalizer].
     */
    fun validate(type: PrimaryCredentialType, credential: CharArray): CredentialFailure? = when (type) {
        PrimaryCredentialType.Pin -> validatePin(credential)
        PrimaryCredentialType.Password -> validatePassword(credential)
        PrimaryCredentialType.Pattern -> validatePattern(credential)
    }

    /** Validates a numeric PIN: digits only, within bounds, and not an obvious guess. */
    fun validatePin(pin: CharArray): CredentialFailure? {
        if (pin.size < minimumPinLength) return CredentialFailure.TooShort(minimumPinLength)
        if (pin.size > maximumPinLength) return CredentialFailure.TooLong(maximumPinLength)
        if (pin.any { digit -> digit !in '0'..'9' }) return CredentialFailure.InvalidCharacters
        if (isSameDigit(pin)) return CredentialFailure.TooCommon
        if (isStraightRun(pin)) return CredentialFailure.TooCommon
        if (matchesAny(pin, COMMON_PINS, COMMON_PIN_LENGTH)) return CredentialFailure.TooCommon
        return null
    }

    /** Validates a password: within bounds, not blank or control-laden, and not a known guess. */
    fun validatePassword(password: CharArray): CredentialFailure? {
        if (password.size < minimumPasswordLength) return CredentialFailure.TooShort(minimumPasswordLength)
        if (password.size > maximumPasswordLength) return CredentialFailure.TooLong(maximumPasswordLength)
        if (password.all { character -> character.isWhitespace() }) return CredentialFailure.InvalidCharacters
        if (password.any { character -> Character.isISOControl(character) }) return CredentialFailure.InvalidCharacters
        if (matchesAny(password, COMMON_PASSWORDS, COMMON_PASSWORD_LENGTH)) return CredentialFailure.TooCommon
        return null
    }

    /**
     * Validates a canonical pattern: enough points, inside the grid, no point connected twice,
     * and not a full sweep of the grid.
     */
    fun validatePattern(pattern: CharArray): CredentialFailure? {
        if (pattern.size < minimumPatternPoints) return CredentialFailure.PatternTooShort(minimumPatternPoints)
        if (pattern.any { point -> point !in '0'..'8' }) return CredentialFailure.PatternInvalid
        if (hasRepeatedPoint(pattern)) return CredentialFailure.PatternInvalid
        if (isFullSweep(pattern)) return CredentialFailure.TooCommon
        return null
    }

    private fun isSameDigit(credential: CharArray): Boolean =
        credential.isNotEmpty() && credential.all { it == credential[0] }

    /** A run such as `1234`, `4321` or `987654`: every step moves by exactly one in one direction. */
    private fun isStraightRun(credential: CharArray): Boolean {
        if (credential.size < MINIMUM_RUN_LENGTH) return false
        val ascending = credential[1] == credential[0] + 1
        val descending = credential[1] == credential[0] - 1
        if (!ascending && !descending) return false
        return (1 until credential.size).all { index ->
            if (ascending) {
                credential[index] == credential[index - 1] + 1
            } else {
                credential[index] == credential[index - 1] - 1
            }
        }
    }

    private fun hasRepeatedPoint(pattern: CharArray): Boolean {
        // Patterns are short (at most nine points), so a linear scan is cheaper than a set.
        for (index in pattern.indices) {
            for (other in index + 1 until pattern.size) {
                if (pattern[index] == pattern[other]) return true
            }
        }
        return false
    }

    /**
     * `true` for the two trivial sweeps of the whole grid, which are among the first drawings
     * anyone tries. Every other pattern is left to the work factor and the attempt throttle.
     */
    private fun isFullSweep(pattern: CharArray): Boolean =
        pattern.contentEquals(FULL_SWEEP) || pattern.contentEquals(FULL_SWEEP_REVERSED)

    /** Compares a credential against a table without letting the comparison leak more than its result. */
    private fun matchesAny(credential: CharArray, table: List<CharArray>, longest: Int): Boolean {
        if (credential.size > longest) return false
        var match = false
        for (candidate in table) {
            if (candidate.size == credential.size && candidate.contentEquals(credential)) {
                match = true
            }
        }
        return match
    }

    companion object {
        /** Four digits is the shortest length Android itself allows for a device PIN. */
        const val DEFAULT_MINIMUM_PIN_LENGTH: Int = 4

        /** Long enough for a strong PIN, short enough to type comfortably. */
        const val DEFAULT_MAXIMUM_PIN_LENGTH: Int = 12

        /** Length is the only password rule; eight characters is the common floor. */
        const val DEFAULT_MINIMUM_PASSWORD_LENGTH: Int = 8

        /** Bounded so that input cannot be turned into unbounded work. */
        const val DEFAULT_MAXIMUM_PASSWORD_LENGTH: Int = 128

        /** Android's own minimum for a pattern lock. */
        const val DEFAULT_MINIMUM_PATTERN_POINTS: Int = 4

        private const val MINIMUM_RUN_LENGTH = 3
        private const val MINIMUM_DRAWABLE_POINTS = 2

        private val FULL_SWEEP: CharArray = "012345678".toCharArray()
        private val FULL_SWEEP_REVERSED: CharArray = "876543210".toCharArray()

        /**
         * The most commonly chosen PINs, from published analyses of leaked PIN sets.
         *
         * Held as character arrays so a credential never has to be turned into a `String` to be
         * compared against them.
         */
        private val COMMON_PINS: List<CharArray> = listOf(
            "1234", "0000", "1111", "1212", "7777", "1004", "2000", "4444",
            "2222", "6969", "9999", "3333", "5555", "6666", "1122", "1313",
            "8888", "4321", "2001", "1010",
        ).map { it.toCharArray() }

        /** A short list of famously weak passwords. Deliberately short; see the class comment. */
        private val COMMON_PASSWORDS: List<CharArray> = listOf(
            "password", "password1", "password123", "12345678", "123456789", "qwertyuiop",
            "letmein1", "iloveyou", "admin123", "welcome1", "abc12345", "monkey123",
        ).map { it.toCharArray() }

        private val COMMON_PIN_LENGTH: Int = COMMON_PINS.maxOf { it.size }
        private val COMMON_PASSWORD_LENGTH: Int = COMMON_PASSWORDS.maxOf { it.size }

        /** The policy Nivara applies. */
        val Default: CredentialPolicy = CredentialPolicy()
    }
}
