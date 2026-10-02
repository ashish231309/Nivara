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

    /**
     * The record that lists what a vault holds.
     *
     * A purpose of its own rather than [VaultMetadata]: the vault's own record and the list of
     * files inside it are different things with different lifetimes — one is written once when the
     * vault is created, the other grows with every import — and a ciphertext made for one must
     * never be accepted for the other. Since the purpose is authenticated, copying an index
     * envelope into the vault record (or the reverse) fails before anything is decrypted.
     */
    VaultIndex(tag = 0x07),

    /**
     * The record that holds a vault's albums.
     *
     * A third purpose for the vault's metadata, and a third place a ciphertext can belong to: the
     * list of what the vault holds, the vault's own record and the way its owner has organised those
     * files are three different things with three different lifetimes, and a ciphertext made for one
     * must never be accepted for another. Since the purpose is authenticated, moving an album record
     * onto an index slot — or the reverse — fails before anything is decrypted.
     */
    VaultOrganization(tag = 0x08),

    /**
     * The record that holds the items a vault has moved out of its active collection.
     *
     * A fourth purpose for the vault's metadata, and a fourth place a ciphertext can belong to: what
     * the vault holds, how its owner has organised those files, and which files are out of the active
     * collection are three different things with three different lifetimes, and a ciphertext made for
     * one must never be accepted for another. Since the purpose is authenticated, moving a trash record
     * onto an album or index slot — or the reverse — fails before anything is decrypted.
     */
    VaultTrash(tag = 0x09),
    ;

    companion object {
        /** Resolves a purpose tag read from an envelope, or `null` when the tag is unknown. */
        fun fromTag(tag: Int): EncryptionContext? = entries.firstOrNull { it.tag == tag }
    }
}
