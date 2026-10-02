package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.security.SensitiveBytes
import java.security.MessageDigest

/**
 * The human-facing shape of a vault's recovery key.
 *
 * The recovery key itself is 256 random bits (see `RecoveryKeyEnvelopeService`); this codec is only
 * the encoding that lets a person carry those bits out of the app and back in again. It deliberately
 * owns nothing else: it does not generate keys, it does not verify vaults, and it never sees a vault
 * key — it translates between thirty-two bytes and a string, in both directions, with error
 * detection on the way in.
 *
 * ### The encoding
 * The thirty-two key bytes are written in RFC 4648 base32 (the alphabet `A`–`Z` and `2`–`7`),
 * unpadded: fifty-two characters, shown in thirteen groups of four separated by hyphens. A final
 * group carries the first two bytes of SHA-256 of the key, in the same alphabet: four more
 * characters that detect a mistyped or mis-copied code before any vault cryptography is asked to
 * absorb the mistake. The full code is therefore 13 groups, a hyphen, and the checksum group.
 *
 * Decoding is forgiving about presentation — case, hyphens and spaces are ignored — and strict
 * about content: anything outside the alphabet, a wrong length, or a checksum that does not match
 * is a typed refusal, never a guess.
 *
 * ### Why no stretching
 * The secret is 256 bits of secure randomness: there is no dictionary to defend against and no
 * offline attack cheaper than brute force, so the checksum is error detection, not protection —
 * the protection is the envelope's authentication. Attempts are still throttled where the code is
 * consumed, as a rail against automated guessing, not as the lock itself.
 */
object RecoveryCodeCodec {

    /** How many bytes the recovery key holds. 256 bits, matching the envelope service's key. */
    const val KEY_BYTES: Int = 32

    /** Bytes of the key, in base32 without padding. */
    const val KEY_CHARACTERS: Int = 52

    /** Bytes of the checksum, in base32 without padding. */
    const val CHECKSUM_BYTES: Int = 2

    /** Checksum characters in base32 (16 bits needs four). */
    const val CHECKSUM_CHARACTERS: Int = 4

    /** Groups of four the key portion is shown in. */
    const val GROUP_SIZE: Int = 4

    /** What separates groups when a code is shown, and is ignored when one is entered. */
    const val GROUP_SEPARATOR: Char = '-'

    /** The number of characters a complete code carries, ignoring separators. */
    const val TOTAL_CHARACTERS: Int = KEY_CHARACTERS + CHECKSUM_CHARACTERS

    /** A code that decodes: what it is, or why it was refused. */
    sealed class DecodeFailure(message: String) : Exception(message) {

        /** The text is not a recovery code: wrong characters, or the wrong number of them. */
        data object Malformed : DecodeFailure("the text is not a recovery code")

        /** The text is a well-formed code whose checksum does not match its key portion. */
        data object ChecksumMismatch : DecodeFailure("the code does not match its own checksum")
    }

    /**
     * Renders [key] as a recovery code.
     *
     * The key must hold exactly [KEY_BYTES] bytes; anything else is a programming error the caller
     * must not make, reported as a failure rather than encoded.
     */
    fun encode(key: SensitiveBytes): NivaraResult<String> {
        if (key.size != KEY_BYTES || key.isCleared) {
            return NivaraResult.Failure(EncodeFailure.InvalidKeySize)
        }
        val bytes = key.copyBytes()
        try {
            val checksum = sha256(bytes).copyOfRange(0, CHECKSUM_BYTES)
            val body = encodeBase32(bytes)
            val tail = encodeBase32(checksum)
            return NivaraResult.Success(grouped(body) + GROUP_SEPARATOR + tail)
        } finally {
            bytes.fill(0)
        }
    }

    /** Refusals [encode] can return. */
    sealed class EncodeFailure(message: String) : Exception(message) {

        /** The material handed in is not a recovery key: wrong length, or already cleared. */
        data object InvalidKeySize : EncodeFailure("the material is not a recovery key")
    }

    /**
     * Reads a recovery code back into key bytes.
     *
     * Presentation is forgiven — upper and lower case both decode, hyphens and spaces are dropped —
     * but content is not: the surviving characters must all belong to the base32 alphabet, there must
     * be exactly [TOTAL_CHARACTERS] of them, the padding bits the encoding leaves over must be zero,
     * and the checksum group must agree with the key portion. Any miss is a typed [DecodeFailure].
     */
    fun decode(text: String): NivaraResult<SensitiveBytes> {
        val cleaned = StringBuilder(text.length)
        for (character in text) {
            when {
                character == GROUP_SEPARATOR || character == ' ' -> Unit
                else -> cleaned.append(character.uppercaseChar())
            }
        }
        if (cleaned.length != TOTAL_CHARACTERS) {
            return NivaraResult.Failure(DecodeFailure.Malformed)
        }
        val values = IntArray(cleaned.length)
        for (index in cleaned.indices) {
            val value = BASE32_ALPHABET.indexOf(cleaned[index])
            if (value < 0) return NivaraResult.Failure(DecodeFailure.Malformed)
            values[index] = value
        }

        val key = decodeBase32(values, 0, KEY_CHARACTERS, KEY_BYTES)
            ?: return NivaraResult.Failure(DecodeFailure.Malformed)
        val checksum = decodeBase32(values, KEY_CHARACTERS, CHECKSUM_CHARACTERS, CHECKSUM_BYTES)
            ?: return NivaraResult.Failure(DecodeFailure.Malformed)

        val expected = sha256(key).copyOfRange(0, CHECKSUM_BYTES)
        return if (checksum.contentEquals(expected)) {
            NivaraResult.Success(SensitiveBytes.wrap(key))
        } else {
            key.fill(0)
            NivaraResult.Failure(DecodeFailure.ChecksumMismatch)
        }
    }

    private fun grouped(body: String): String {
        val builder = StringBuilder(body.length + body.length / GROUP_SIZE)
        body.forEachIndexed { index, character ->
            if (index > 0 && index % GROUP_SIZE == 0) builder.append(GROUP_SEPARATOR)
            builder.append(character)
        }
        return builder.toString()
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val builder = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (value in bytes) {
            buffer = (buffer shl 8) or (value.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                builder.append(BASE32_ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            builder.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return builder.toString()
    }

    /**
     * Decodes [count] base32 digit values starting at [from] into exactly [expectedBytes] bytes.
     *
     * Returns null when the digits cannot encode that many bytes cleanly — including when the
     * leftover padding bits are not zero, which a genuine encoder never produces.
     */
    private fun decodeBase32(
        values: IntArray,
        from: Int,
        count: Int,
        expectedBytes: Int,
    ): ByteArray? {
        if (count * 5 < expectedBytes * 8) return null
        val out = ByteArray(expectedBytes)
        var buffer = 0
        var bitsLeft = 0
        var written = 0
        for (index in from until from + count) {
            buffer = (buffer shl 5) or values[index]
            bitsLeft += 5
            if (bitsLeft >= 8) {
                if (written >= out.size) return null
                out[written++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }
        if (written != expectedBytes) return null
        if (bitsLeft != 0 && (buffer and ((1 shl bitsLeft) - 1)) != 0) return null
        return out
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private const val BASE32_ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
}
