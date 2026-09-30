package com.nivara.app.domain.security

/**
 * The purpose an encrypted value belongs to, and the basis for binding ciphertext to context.
 *
 * Every encrypted Nivara value becomes an envelope that names exactly one of these purposes. The
 * purpose is written into the authenticated part of the envelope, so a ciphertext produced for
 * one purpose cannot be silently accepted for another: re-labelling it breaks the
 * authentication tag, and relocating it (for example copying a vault metadata envelope into the
 * recovery slot) is rejected before any decryption is attempted.
 *
 * Tags are part of the on-disk format. Values must never be renumbered; new purposes get new
 * unused numbers.
 */
enum class EncryptionContext(val tag: Int) {

    /** File data stored inside the vault. */
    VaultContent(tag = 0x01),

    /** Index and descriptor records describing vault contents. */
    VaultMetadata(tag = 0x02),

    /** The recovery envelope that protects a vault key with a recovery key. */
    RecoveryEnvelope(tag = 0x03),

    /** Enrolled credentials, lock settings and other application security state. */
    ApplicationSecurityData(tag = 0x04),

    /** A wrapped (protected) content key. */
    KeyWrapping(tag = 0x05),

    /** Key material guarded by an Android Keystore key. */
    DeviceProtectedKey(tag = 0x06),
    ;

    companion object {
        /** Resolves a purpose tag read from an envelope, or `null` when the tag is unknown. */
        fun fromTag(tag: Int): EncryptionContext? = entries.firstOrNull { it.tag == tag }
    }
}
