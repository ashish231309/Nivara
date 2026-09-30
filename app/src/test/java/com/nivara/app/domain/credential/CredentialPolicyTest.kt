package com.nivara.app.domain.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the enrollment rules.
 *
 * The rules are the cheap, deterministic filter in front of the expensive one: they stop obvious
 * mistakes, and the key derivation plus attempt throttling carry the real cost of guessing. They
 * are checked here without any Android or cryptographic dependency, because that is how they are
 * written.
 */
class CredentialPolicyTest {

    private val policy = CredentialPolicy.Default

    // ------------------------------------------------------------------ PIN

    @Test
    fun `accepts a PIN that is not an obvious choice`() {
        assertNull(policy.validatePin("573108".toCharArray()))
        assertNull(policy.validatePin("9183".toCharArray()))
    }

    @Test
    fun `rejects a PIN that is too short`() {
        val failure = policy.validatePin("123".toCharArray())

        assertEquals(CredentialFailure.TooShort(policy.minimumPinLength), failure)
    }

    @Test
    fun `rejects a PIN that is too long`() {
        val failure = policy.validatePin("1234567890123".toCharArray())

        assertEquals(CredentialFailure.TooLong(policy.maximumPinLength), failure)
    }

    @Test
    fun `rejects a PIN containing anything but digits`() {
        assertEquals(CredentialFailure.InvalidCharacters, policy.validatePin("12a4".toCharArray()))
        assertEquals(CredentialFailure.InvalidCharacters, policy.validatePin("12 4".toCharArray()))
    }

    @Test
    fun `rejects a repeated digit`() {
        assertEquals(CredentialFailure.TooCommon, policy.validatePin("0000".toCharArray()))
        assertEquals(CredentialFailure.TooCommon, policy.validatePin("777777".toCharArray()))
    }

    @Test
    fun `rejects a straight run of digits`() {
        assertEquals(CredentialFailure.TooCommon, policy.validatePin("1234".toCharArray()))
        assertEquals(CredentialFailure.TooCommon, policy.validatePin("9876".toCharArray()))
    }

    @Test
    fun `rejects the most commonly chosen PINs`() {
        assertEquals(CredentialFailure.TooCommon, policy.validatePin("1212".toCharArray()))
        assertEquals(CredentialFailure.TooCommon, policy.validatePin("6969".toCharArray()))
    }

    @Test
    fun `accepts a PIN that only looks like a run but is not consecutive`() {
        assertNull(policy.validatePin("1357".toCharArray()))
        assertNull(policy.validatePin("1235".toCharArray()))
    }

    // ------------------------------------------------------------------ password

    @Test
    fun `accepts a password that is long enough and not a known guess`() {
        assertNull(policy.validatePassword("correct horse battery".toCharArray()))
        assertNull(policy.validatePassword("yT7#kLp2qW".toCharArray()))
    }

    @Test
    fun `rejects a password that is too short`() {
        assertEquals(
            CredentialFailure.TooShort(policy.minimumPasswordLength),
            policy.validatePassword("short".toCharArray()),
        )
    }

    @Test
    fun `rejects a password that is too long`() {
        val long = CharArray(policy.maximumPasswordLength + 1) { 'a' }

        // A long run of one character is also weak, so the length bound is what is checked here.
        assertEquals(CredentialFailure.TooLong(policy.maximumPasswordLength), policy.validatePassword(long))
    }

    @Test
    fun `rejects a blank password`() {
        assertEquals(CredentialFailure.InvalidCharacters, policy.validatePassword("        ".toCharArray()))
    }

    @Test
    fun `rejects control characters in a password`() {
        assertEquals(CredentialFailure.InvalidCharacters, policy.validatePassword("abc\u0000defgh".toCharArray()))
    }

    @Test
    fun `rejects a famously weak password`() {
        assertEquals(CredentialFailure.TooCommon, policy.validatePassword("password".toCharArray()))
        assertEquals(CredentialFailure.TooCommon, policy.validatePassword("qwertyuiop".toCharArray()))
    }

    @Test
    fun `accepts a weak-looking password that is not on the list`() {
        // No character-class rules: length is the requirement, and a long simple phrase passes.
        assertNull(policy.validatePassword("aaaaaaaaaaaa".toCharArray()))
    }

    // ------------------------------------------------------------------ pattern

    @Test
    fun `accepts a pattern with enough points`() {
        assertNull(policy.validatePattern("0481".toCharArray()))
    }

    @Test
    fun `rejects a pattern with too few points`() {
        assertEquals(
            CredentialFailure.PatternTooShort(policy.minimumPatternPoints),
            policy.validatePattern("012".toCharArray()),
        )
    }

    @Test
    fun `rejects a pattern that repeats a point`() {
        assertEquals(CredentialFailure.PatternInvalid, policy.validatePattern("0121".toCharArray()))
    }

    @Test
    fun `rejects a pattern outside the grid`() {
        assertEquals(CredentialFailure.PatternInvalid, policy.validatePattern("01:9".toCharArray()))
    }

    @Test
    fun `rejects a full sweep of the grid`() {
        assertEquals(CredentialFailure.TooCommon, policy.validatePattern("012345678".toCharArray()))
        assertEquals(CredentialFailure.TooCommon, policy.validatePattern("876543210".toCharArray()))
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    fun `dispatch validation uses the rules of the given method`() {
        assertEquals(
            CredentialFailure.TooShort(policy.minimumPinLength),
            policy.validate(PrimaryCredentialType.Pin, "12".toCharArray()),
        )
        assertEquals(
            CredentialFailure.TooShort(policy.minimumPasswordLength),
            policy.validate(PrimaryCredentialType.Password, "12".toCharArray()),
        )
        assertEquals(
            CredentialFailure.PatternTooShort(policy.minimumPatternPoints),
            policy.validate(PrimaryCredentialType.Pattern, "12".toCharArray()),
        )
    }

    @Test
    fun `a custom policy is honoured`() {
        val strict = CredentialPolicy(minimumPasswordLength = 20)

        assertTrue(strict.validatePassword("onlysixteenchars".toCharArray()) is CredentialFailure.TooShort)
        assertNull(strict.validatePassword("this password is long enough".toCharArray()))
    }
}
