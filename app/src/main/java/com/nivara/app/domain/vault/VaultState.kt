package com.nivara.app.domain.vault

/**
 * What is at the vault root Nivara is pointed at, and whether it can be opened.
 *
 * ```
 *  NotConfigured       no root has been chosen yet
 *  LocationUnknown     a root was chosen, and the record of which one cannot be read
 *  Missing             a root is chosen and there is no Nivara vault at it yet
 *  Ready               a valid vault: authenticated metadata and the structure it requires
 *  Unreadable          vault records exist, and none of them can be opened
 *  UnsupportedVersion  vault records exist in a format this build does not know
 *  Unavailable         the root cannot be reached at all right now
 *  AccessDenied        the platform refuses access to the root
 * ```
 *
 * ### Why this is not a boolean
 *
 * The whole point of this stage is that *no vault here* and *a vault I cannot read* are different
 * facts with different consequences. Collapsing them — into an empty vault, an empty list, or a
 * `null` — would present file loss as an empty vault and an unreachable card as a missing one. Every
 * reason Nivara can give is therefore its own case, and the states that mean "something is wrong"
 * carry the reason rather than a message.
 *
 * ### What a state is not
 *
 * None of these is an authorization. A vault can be [Ready] while the session gate is closed: the
 * state says where the vault is and whether it can be opened as a vault, not whether the user may
 * look inside it. That decision belongs to the screens, and they ask
 * [com.nivara.app.domain.security.SessionManager] for it exactly as every other protected feature
 * does.
 */
sealed interface VaultState {

    /** No vault root has been chosen. Nothing has been looked for, and nothing exists. */
    data object NotConfigured : VaultState

    /**
     * A root was chosen, but the record of *which* root cannot be read.
     *
     * Not the same as [NotConfigured]: a vault may exist at a location Nivara can no longer name. The
     * only honest thing to do is say so and let the user point at the folder again — never to forget
     * the selection and start over, and never to guess a location.
     */
    data object LocationUnknown : VaultState

    /**
     * The root is reachable and holds no Nivara vault.
     *
     * This is the state a fresh, empty folder is in, and the only state in which a vault may be
     * created. It is a confident answer: the metadata area was looked for and is not there.
     */
    data object Missing : VaultState

    /**
     * A valid vault.
     *
     * @param identity the vault's non-secret identifier, created when the vault was initialized.
     *   It is not a key, not a credential and not a location; later stages use it to recognise the
     *   vault when an envelope or a recovery card refers to one.
     * @param formatVersion the metadata format version this vault was written in. It equals the
     *   version this build writes; a newer one is reported as [UnsupportedVersion] instead.
     */
    data class Ready(
        val identity: VaultIdentity,
        val formatVersion: Int,
    ) : VaultState

    /**
     * The root holds Nivara vault records, and none of them can be opened.
     *
     * Reported with the reason, because the three reasons have three different remedies and none of
     * them is "the vault is empty". See [VaultUnreadable].
     */
    data class Unreadable(val reason: VaultUnreadable) : VaultState

    /**
     * The root holds a Nivara vault written in a format this build does not know.
     *
     * Refusing is the only safe answer: a newer Nivara may have written data this build cannot read,
     * and treating the record as damaged — or replacing it — would destroy a vault that a later
     * version can still open.
     *
     * @param fileVersion the version byte read from the record.
     */
    data class UnsupportedVersion(val fileVersion: Int) : VaultState

    /**
     * The root cannot be reached at all: the volume is gone, the provider is not answering, or the
     * folder no longer exists.
     *
     * Distinct from [AccessDenied] because the remedy differs — reconnect the storage versus restore
     * the grant — and distinct from [Missing] because a vault may well be there and simply not
     * reachable right now.
     */
    data object Unavailable : VaultState

    /**
     * The platform refuses access to the root: the persisted permission was revoked, or the
     * selection can no longer be resolved to a folder Nivara may use.
     */
    data object AccessDenied : VaultState
}

/**
 * Why a vault that exists cannot be opened.
 *
 * Each case is a fact about the vault's files or key material, never a guess:
 *
 * * [MetadataDamaged] — a record carries Nivara's marker and cannot be authenticated or parsed.
 * * [StructureIncomplete] — the metadata is valid and a required part of the vault's structure is
 *   absent, which means something was deleted or an initialization did not finish.
 * * [KeyUnavailable] — the vault is structurally intact, but the platform key that protects its key
 *   material is gone or unusable (removing the screen lock destroys such keys). The vault may still
 *   be recoverable through another wrapping in a later stage; it is not damaged.
 */
enum class VaultUnreadable {

    /** A metadata record carries Nivara's marker and cannot be validated. */
    MetadataDamaged,

    /** The metadata is valid and a required directory is missing. */
    StructureIncomplete,

    /** The platform key that protects the vault's key material is gone or cannot be used. */
    KeyUnavailable,
}
