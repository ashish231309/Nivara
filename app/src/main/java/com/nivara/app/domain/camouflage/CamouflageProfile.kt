package com.nivara.app.domain.camouflage

/**
 * An identity Nivara can present to the device's launcher.
 *
 * ```
 *  launcher entry, as the user sees it
 *  ────────────────────────────────────────────────────────────────
 *  Nivara       the application's own name and icon
 *  Notes        a benign, ordinary utility identity
 *  Calculator   a benign, ordinary utility identity
 *  Weather      a benign, ordinary utility identity
 * ```
 *
 * ### What an identity is, and what it is not
 *
 * An identity is **presentation**: the label and the icon of the launcher entry that starts Nivara.
 * It is not the application's package, not a second application, and not a security boundary. The
 * package `com.nivara.app` is the same before and after a change, the signature is the same, the
 * data is the same, and every Android surface that lists installed applications — Settings, the
 * package manager, ADB, a device administrator, a managed profile's administrator — still lists
 * Nivara by its package name. Camouflage changes what the home screen shows and nothing else.
 *
 * [CamouflageProfile.Nivara] is therefore not "camouflage off" in a second code path: it is one of
 * the identities, the one whose label and icon are Nivara's own, and restoring it is an ordinary
 * selection like any other.
 *
 * ### Why the set is small and fixed
 *
 * Four identities are declared as Android components in the manifest, so each one is a name and an
 * icon the platform already knows about, and choosing one is a single platform decision with no
 * Nivara-owned state to write, migrate, corrupt or lose. An open-ended profile framework would add
 * a store, a format, a migration path and a failure mode to answer a question that four constants
 * answer completely.
 *
 * ### What must never be stored here
 *
 * An identity carries no credential, no key, no biometric material, no session, no hidden or
 * protected application, and no package name. It is a name, an icon and a preference — and the
 * preference is held by the platform, not by Nivara (see `CamouflageRepository`).
 *
 * @property id the stable identifier, used to derive the identity's Android component name. It is
 *   lower-case, never displayed, and never a package name.
 */
enum class CamouflageProfile(val id: String) {

    /** Nivara's own identity: the label and icon the application ships with. */
    Nivara("nivara"),

    /** A notes identity. Ordinary, unremarkable, and not a claim about the application. */
    Notes("notes"),

    /** A calculator identity. */
    Calculator("calculator"),

    /** A weather identity. */
    Weather("weather"),
    ;

    /** Whether this identity is one of the benign ones rather than Nivara's own. */
    val isCamouflage: Boolean get() = this != Nivara

    companion object {

        /**
         * The identity Nivara presents unless the user chooses another one.
         *
         * The default is Nivara's own, and every rule that cannot determine what the device is
         * presenting resolves to this value: a stranded entry, an ambiguous set of enabled entries
         * and a device Nivara cannot read are all returned to the identity the user can always
         * recognise. A camouflage identity is never chosen for the user — not on a fresh install,
         * and not as a repair.
         */
        val Default: CamouflageProfile = Nivara

        /** The benign identities, in the order the configuration screen offers them. */
        val camouflageProfiles: List<CamouflageProfile> get() = entries.filter { it.isCamouflage }
    }
}
