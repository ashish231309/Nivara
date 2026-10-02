package com.nivara.app.domain.camouflage

import com.nivara.app.core.common.NivaraResult

/**
 * The identity Nivara presents to the device's launcher.
 *
 * ### Where the selection is kept
 *
 * Nowhere Nivara owns. The chosen identity is the enabled state of one Android component the
 * application declares in its manifest, so the platform is the store: a change is a single platform
 * call, there is no file to write atomically, no version to migrate, no checksum to verify and no
 * format that can be corrupted. That is a deliberate choice rather than a missing layer — a second
 * copy of the selection inside Nivara would be exactly the kind of state that can disagree with what
 * the launcher is showing, and agreeing with what the launcher is showing is the whole feature.
 *
 * Two consequences follow, and both are intended:
 *
 * * **Reading is authoritative.** [currentProfile] asks the platform what is enabled now, rather
 *   than remembering what the user once chose. An identity changed outside Nivara — by Android, by
 *   a restore, by a tool — is reported as it is.
 * * **There is nothing to migrate.** An identity that is no longer declared cannot be read as an
 *   identity at all, so a removed identity resolves to the default rather than to a name that no
 *   longer exists.
 *
 * ### What a change does, and what it does not
 *
 * Selecting an identity enables the launcher entry that carries it and disables the others. It
 * changes nothing else: no package, no signature, no component of another application, no default
 * launcher selection, no setting outside Nivara's own declared components, and nothing about
 * protection, hiding or the session. It is reversible by selecting another identity, and the
 * application's own identity is always one of the identities that can be selected.
 *
 * ### The guarantee this contract carries
 *
 * At least one launcher entry for Nivara is always enabled, and the one that is always available is
 * Nivara's own. The transition enables the entry it is moving to before disabling the one it is
 * leaving, so an interrupted change can leave two entries but can never leave none; and a device
 * state that describes no identity, or more than one, is repaired back to the default rather than
 * presented as an identity the user never chose.
 */
interface CamouflageRepository {

    /**
     * The identity the device is presenting right now.
     *
     * Never throws and never returns a guess. When the platform's component states do not describe a
     * single identity — nothing enabled, or several — the default identity is re-applied and
     * returned, so the caller can always draw an answer the user can act on.
     */
    suspend fun currentProfile(): CamouflageProfile

    /**
     * Presents [profile] from now on.
     *
     * A failure means the platform refused the change and the device may be left on the previous
     * identity; the caller must not report the change it asked for, and should re-read
     * [currentProfile] instead of assuming either outcome.
     */
    suspend fun selectProfile(profile: CamouflageProfile): NivaraResult<Unit>
}
