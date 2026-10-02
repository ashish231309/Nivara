package com.nivara.app.domain.vault

/**
 * Everything that can go wrong when Nivara is asked to change something about the vault.
 *
 * These are failures of an *operation* — storing a location, creating a vault — and each one has a
 * different next step, which is why they are not a single "vault error" and not an exception carrying
 * a stack trace. Like every other typed failure in Nivara, the messages are fixed, non-secret
 * strings: no path, no URI, no key material, no platform exception text.
 *
 * A failure never means "the vault is empty". That is the one interpretation this stage exists to
 * make impossible, and there is no case in this type that could be read that way.
 */
sealed class VaultFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The selection is not a folder Nivara can use as a vault root.
     *
     * Reported when the reference is not a document tree, or names something that cannot hold
     * children — a file, or a location the platform refuses to treat as a directory.
     */
    data object InvalidLocation :
        VaultFailure("the selection is not a usable vault root")

    /**
     * The stored record of the selected location cannot be read.
     *
     * The record is left exactly as it was found: it is the only thing that still says where the
     * vault is, and a damaged record is not evidence that there is no vault.
     */
    data object LocationUnreadable :
        VaultFailure("the stored vault location cannot be read")

    /**
     * The platform refuses access to the root: the persisted grant was revoked, or the selection can
     * no longer be resolved.
     *
     * The user's remedy is to point at the folder again — Nivara never works around a refusal, and
     * never falls back to another location it happens to be able to open.
     */
    data object AccessDenied :
        VaultFailure("the platform refuses access to the vault root")

    /**
     * The root cannot be reached: the volume is gone, the provider is not answering, or the folder no
     * longer exists. Nothing is claimed about the vault and nothing is written.
     */
    data object StorageUnavailable :
        VaultFailure("the vault root cannot be reached")

    /**
     * A valid vault already exists at the root.
     *
     * Initialization refuses rather than replacing it. Replacing a valid vault destroys the key
     * material every stored file depends on, and "I asked for a new vault" is never a good enough
     * reason to do that silently.
     */
    data object VaultAlreadyExists :
        VaultFailure("a vault already exists at this root")

    /**
     * The root holds Nivara vault records and none of them can be opened.
     *
     * Initialization refuses unless the caller has explicitly said that the unreadable records may be
     * replaced — see [VaultRepository.initialize].
     */
    data class VaultUnreadable(val reason: VaultUnreadableReason) :
        VaultFailure("a vault exists at this root and cannot be opened")

    /**
     * The root holds a vault written in a format this build does not know.
     *
     * Never replaceable: a newer Nivara wrote it, and destroying it would lose a vault that a later
     * version can still open.
     */
    data class UnsupportedVersion(val fileVersion: Int) :
        VaultFailure("the vault at this root was written by a newer Nivara")

    /**
     * The platform refused a write, or a directory could not be created.
     *
     * Reported for the exact step that failed; nothing that did not happen is reported as done.
     */
    data object WriteFailed :
        VaultFailure("the platform refused a write to the vault root")

    /**
     * A record was written and did not validate when it was read back.
     *
     * This is the failure that keeps "the vault is initialized" honest: the state is committed only
     * after the bytes on the chosen storage have been read again and authenticated, so a write that
     * silently did not land is reported as a failure rather than as a vault.
     */
    data object VerificationFailed :
        VaultFailure("a written vault record did not validate")

    /**
     * The platform key that protects the vault's key material could not be created or used.
     *
     * On Android this happens when the user removes their screen lock: the platform destroys keys it
     * can no longer protect. Nothing is written and nothing is repaired — the vault may still be
     * openable through another wrapping in a later stage.
     */
    data object KeyUnavailable :
        VaultFailure("the platform key protecting the vault is not available")

    /**
     * One of the existing cryptographic services refused the operation.
     *
     * Carried through rather than translated, so a failure of the Stage 2 layer cannot be mistaken
     * for a storage problem. Its cause (if any) stays inside the failure object and never reaches a
     * message.
     */
    data object CryptographyFailed :
        VaultFailure("a cryptographic service refused the operation")
}
