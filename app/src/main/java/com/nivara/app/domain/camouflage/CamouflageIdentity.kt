package com.nivara.app.domain.camouflage

/**
 * What the device says about the launcher entry Nivara is presenting.
 *
 * The platform holds the answer — exactly one of the application's launcher components is enabled,
 * and the identity that component carries is the identity the user sees — so the only two facts a
 * reader can be given are "this identity is active" and "the entries are in a state Nivara cannot
 * present as an identity".
 *
 * ### Why a broken state is not a guessed identity
 *
 * A device can be left with no launcher entry for Nivara (every component disabled, for instance by
 * a tool that reset the application's components) or with several (a change that was interrupted
 * between enabling the new entry and disabling the old one). Neither state is an identity. Reading
 * either as one would mean presenting a name the user did not choose, which is the opposite of what
 * camouflage is for, so both resolve to [NeedsRepair] — and the repair is always the same one:
 * Nivara's own identity, which exists, is recognisable, and can be reached.
 */
sealed interface CamouflageIdentity {

    /**
     * Exactly one launcher entry is enabled, and it carries [profile].
     *
     * @property profile the identity the device is presenting.
     */
    data class Active(val profile: CamouflageProfile) : CamouflageIdentity

    /**
     * The enabled entries do not describe a single identity, so the default one has to be
     * re-applied before anything is claimed about what the user sees.
     */
    data object NeedsRepair : CamouflageIdentity
}

/**
 * Resolves what the device is presenting from the set of identities whose launcher entry is
 * enabled.
 *
 * This is the whole of the rule, and it is a pure function on purpose: the decision that decides
 * which name and icon stand for Nivara is then testable without a device, and there is exactly one
 * answer to it — no screen, no repository implementation and no repair path decides it again.
 *
 * ```
 *  one   → that identity
 *  none  → NeedsRepair   (there is no entry to present, so the default is re-applied)
 *  many  → NeedsRepair   (the state does not name one identity, so the default is re-applied)
 * ```
 *
 * The set is typed, so an identity Nivara does not declare cannot appear in it; the data layer is
 * where an Android component that matches no declared identity is dropped rather than guessed at.
 */
fun resolveCamouflageIdentity(enabled: Set<CamouflageProfile>): CamouflageIdentity =
    when (enabled.size) {
        1 -> CamouflageIdentity.Active(enabled.first())
        else -> CamouflageIdentity.NeedsRepair
    }
