package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import java.security.MessageDigest

/**
 * Identifies a sealed-key container family, so a blob sealed for one purpose can never be opened
 * as if it were another.
 *
 * @param magic four ASCII bytes written at the start of every container of this family.
 * @param infoPrefix HKDF domain-separation label; different families derive unrelated subkeys even
 *   if they somehow share a wrapping key.
 * @param context purpose tag carried in the container and verified when it is opened.
 */
internal class SealedKeyFormat(
    val magic: ByteArray,
    val infoPrefix: String,
    val context: EncryptionContext,
) {
    companion object {
        /** A content key wrapped by another key (credential-derived, or another content key). */
        val KeyWrapping = SealedKeyFormat(
            magic = "NVKW".toByteArray(Charsets.US_ASCII),
            infoPrefix = "nivara.hkdf.keywrap.v1",
            context = EncryptionContext.KeyWrapping,
        )

        /** A content key sealed by a recovery key. */
        val Recovery = SealedKeyFormat(
            magic = "NVRK".toByteArray(Charsets.US_ASCII),
            infoPrefix = "nivara.hkdf.recovery.v1",
            context = EncryptionContext.RecoveryEnvelope,
        )
    }
}

/**
 * Sealed-key container, version 1: a 256-bit key protected by another 256-bit key.
 *
 * ```
 * offset  0        4        5         6         7          8             24            56        88
 *         | magic  | version| scheme  | reserved| contextTag | nonce (16) | wrapped key (32) | tag (32) |
 * ```
 *
 * Construction — encrypt-then-MAC with two subkeys derived from the wrapping key:
 *
 * ```
 * xorKey        = HKDF-SHA-256(IKM = wrapping key, salt = nonce, info = "<prefix>.xor")
 * macKey        = HKDF-SHA-256(IKM = wrapping key, salt = nonce, info = "<prefix>.mac")
 * wrappedKey    = content key XOR xorKey
 * tag           = HMAC-SHA-256(macKey, container[0 .. 56))
 * ```
 *
 * Why this shape rather than nesting another AES-GCM envelope:
 *
 * - it keeps the wrapped size identical to the key size (32 bytes of material protected by a
 *   32-byte tag), which is what a future vault index and recovery card need;
 * - the construction is a Keystream + HMAC (encrypt-then-MAC), so a wrong wrapping key or a
 *   single flipped bit is rejected by the tag instead of yielding a plausible-looking key;
 * - it needs no AES block-mode concerns for a 32-byte payload, and it stays usable if a future
 *   key size is not a multiple of the AES block size.
 *
 * The nonce is fresh for every sealing and is what makes two seals of the same key differ. This
 * class performs no I/O and no persistence: a container is only ever produced for a caller that
 * already has a place to keep it, and the raw content key never appears inside it.
 */
internal object SealedKeyContainer {

    const val VERSION: Int = 1
    const val SCHEME_HKDF_SHA256_XOR_HMAC_SHA256: Int = 1

    const val NONCE_LENGTH: Int = 16
    const val KEY_LENGTH: Int = 32
    const val TAG_LENGTH: Int = 32

    /** Fixed part of the header, before the nonce. */
    const val HEADER_LENGTH: Int = 8
    private const val NONCE_OFFSET = HEADER_LENGTH
    private const val WRAPPED_OFFSET = NONCE_OFFSET + NONCE_LENGTH
    private const val TAG_OFFSET = WRAPPED_OFFSET + KEY_LENGTH

    /** Every container is exactly this long; there is no variable-length part to guess at. */
    const val LENGTH: Int = TAG_OFFSET + TAG_LENGTH

    private const val VERSION_OFFSET = 4
    private const val SCHEME_OFFSET = 5
    private const val RESERVED_OFFSET = 6
    private const val CONTEXT_OFFSET = 7
    private const val RESERVED_VALUE = 0

    /** `true` when [bytes] starts with this family's magic, without validating the rest. */
    fun hasMagic(bytes: ByteArray, format: SealedKeyFormat): Boolean = bytes.hasPrefix(format.magic)

    /**
     * Seals [contentKey] under [wrappingKey].
     *
     * [nonce] must be fresh for every call; production code passes 16 random bytes from
     * [com.nivara.app.domain.security.SecureRandomGenerator].
     */
    fun seal(
        format: SealedKeyFormat,
        wrappingKey: ByteArray,
        contentKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        if (nonce.size != NONCE_LENGTH) throw CryptographicFailure.InvalidParameters
        if (contentKey.size != KEY_LENGTH) throw CryptographicFailure.InvalidKey
        if (wrappingKey.size != KEY_LENGTH) throw CryptographicFailure.InvalidKey

        val xorKey = Hkdf.deriveKey(
            ikm = wrappingKey,
            salt = nonce,
            info = info(format, "xor"),
            length = KEY_LENGTH,
        )
        val macKey = Hkdf.deriveKey(
            ikm = wrappingKey,
            salt = nonce,
            info = info(format, "mac"),
            length = KEY_LENGTH,
        )
        try {
            val header = header(format = format, nonce = nonce)
            val wrappedKey = xor(contentKey, xorKey)
            val tag = Hkdf.hmac(key = macKey, data = header + wrappedKey)

            val container = ByteArray(LENGTH)
            header.copyInto(container, 0)
            wrappedKey.copyInto(container, WRAPPED_OFFSET)
            tag.copyInto(container, TAG_OFFSET)
            return container
        } finally {
            xorKey.fill(0)
            macKey.fill(0)
        }
    }

    /**
     * Opens a container and returns the raw content key.
     *
     * The container is validated structurally, the tag is verified in constant time, and only then
     * is the content key reconstructed. A wrong wrapping key, an edited nonce, an edited header or
     * an edited body all produce [CryptographicFailure.AuthenticationFailed].
     */
    fun open(
        format: SealedKeyFormat,
        wrappingKey: ByteArray,
        container: ByteArray,
    ): ByteArray {
        validate(format = format, container = container)
        if (wrappingKey.size != KEY_LENGTH) throw CryptographicFailure.InvalidKey

        val nonce = container.copyOfRange(NONCE_OFFSET, WRAPPED_OFFSET)
        val macKey = Hkdf.deriveKey(
            ikm = wrappingKey,
            salt = nonce,
            info = info(format, "mac"),
            length = KEY_LENGTH,
        )
        try {
            val expectedTag = Hkdf.hmac(
                key = macKey,
                data = container.copyOfRange(0, TAG_OFFSET),
            )
            val presentedTag = container.copyOfRange(TAG_OFFSET, LENGTH)
            if (!MessageDigest.isEqual(expectedTag, presentedTag)) {
                throw CryptographicFailure.AuthenticationFailed
            }
        } finally {
            macKey.fill(0)
        }

        val xorKey = Hkdf.deriveKey(
            ikm = wrappingKey,
            salt = nonce,
            info = info(format, "xor"),
            length = KEY_LENGTH,
        )
        try {
            return xor(container.copyOfRange(WRAPPED_OFFSET, TAG_OFFSET), xorKey)
        } finally {
            xorKey.fill(0)
        }
    }

    private fun validate(format: SealedKeyFormat, container: ByteArray) {
        if (container.size != LENGTH) throw CryptographicFailure.MalformedEnvelope
        if (!container.hasPrefix(format.magic)) throw CryptographicFailure.UnsupportedEnvelope
        if (container[VERSION_OFFSET].toInt() != VERSION) throw CryptographicFailure.UnsupportedVersion
        if (container[SCHEME_OFFSET].toInt() != SCHEME_HKDF_SHA256_XOR_HMAC_SHA256) {
            throw CryptographicFailure.UnsupportedKeyScheme
        }
        if (container[RESERVED_OFFSET].toInt() != RESERVED_VALUE) {
            throw CryptographicFailure.MalformedEnvelope
        }
        val declaredContext = EncryptionContext.fromTag(container[CONTEXT_OFFSET].toInt() and 0xFF)
            ?: throw CryptographicFailure.UnsupportedContext
        if (declaredContext != format.context) throw CryptographicFailure.ContextMismatch
    }

    private fun header(format: SealedKeyFormat, nonce: ByteArray): ByteArray {
        val header = ByteArray(WRAPPED_OFFSET)
        format.magic.copyInto(header, 0)
        header[VERSION_OFFSET] = VERSION.toByte()
        header[SCHEME_OFFSET] = SCHEME_HKDF_SHA256_XOR_HMAC_SHA256.toByte()
        header[RESERVED_OFFSET] = RESERVED_VALUE.toByte()
        header[CONTEXT_OFFSET] = format.context.tag.toByte()
        nonce.copyInto(header, NONCE_OFFSET)
        return header
    }

    private fun info(format: SealedKeyFormat, label: String): ByteArray =
        "${format.infoPrefix}.$label".toByteArray(Charsets.UTF_8)

    private fun xor(left: ByteArray, right: ByteArray): ByteArray =
        ByteArray(left.size) { index -> (left[index].toInt() xor right[index].toInt()).toByte() }
}
