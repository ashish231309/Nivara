package com.nivara.app.data.camouflage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.camouflage.CamouflageIdentity
import com.nivara.app.domain.camouflage.CamouflageProfile
import com.nivara.app.domain.camouflage.CamouflageRepository
import com.nivara.app.domain.camouflage.resolveCamouflageIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presents the selected identity by enabling one of the launcher entries Nivara declares.
 *
 * ### The mechanism
 *
 * ```
 *  CamouflageProfile → Android component → the launcher entry the user sees
 *  ─────────────────────────────────────────────────────────────────────────
 *  Nivara            .MainActivity              Nivara's own name and icon
 *  Notes             .CamouflageNotes           the notes identity
 *  Calculator        .CamouflageCalculator      the calculator identity
 *  Weather           .CamouflageWeather         the weather identity
 * ```
 *
 * The three benign identities are `activity-alias` elements targeting `.MainActivity` — no second
 * activity class exists, no second task, and no second copy of anything. An alias is a second way of
 * *presenting and starting the same component*, which is exactly what camouflage is: the same
 * application under a different name and icon. Selecting an identity enables the alias that carries
 * it and disables the other launcher entries, and the platform remembers that for us.
 *
 * ### Why the aliases are declared disabled
 *
 * `android:enabled="false"` in the manifest means the shipped default is Nivara's own identity:
 * on a fresh install exactly one launcher entry is enabled, without Nivara having to normalise
 * anything at start-up. The platform reports `COMPONENT_ENABLED_STATE_DEFAULT` for a component the
 * user has never touched, and for that value the manifest's declared state is the effective one —
 * enabled for `.MainActivity`, disabled for every alias. That mapping is why the verifier pins the
 * manifest: if an alias were declared enabled, a fresh install would show four Nivara entries.
 *
 * ### The two safety rules
 *
 * * **Enable before disabling.** A change enables the entry it is moving to and only then disables
 *   the entries it is leaving, so a change interrupted by a crash, a reboot or a kill leaves two
 *   entries rather than none. Two entries are repaired by the next read; none would strand the user
 *   with no way to open the application at all.
 * * **Only Nivara's own entries.** The only components this class ever changes are the ones listed
 *   above. The Home activity Stage 11 added is not among them and is never named here: the device's
 *   Home contract does not change because of an identity, and the verifier refuses any camouflage
 *   source that mentions it.
 */
internal class AndroidCamouflageRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CamouflageRepository {

    private val packageManager: PackageManager get() = context.packageManager

    /**
     * The launcher entry of [profile], as a component of this application.
     *
     * Nivara's own identity is the application's ordinary entry activity; the others are derived
     * from the profile's identifier, so a declared profile and a declared alias cannot drift apart
     * without the build failing to resolve the component at all.
     */
    private fun componentFor(profile: CamouflageProfile): ComponentName = ComponentName(
        context.packageName,
        if (profile == CamouflageProfile.Nivara) {
            "${context.packageName}.$REAL_ENTRY_COMPONENT"
        } else {
            "${context.packageName}.$CAMOUFLAGE_ALIAS_PREFIX" +
                profile.id.replaceFirstChar { character -> character.uppercase() }
        },
    )

    override suspend fun currentProfile(): CamouflageProfile = withContext(dispatcher) {
        when (val identity = resolveCamouflageIdentity(enabledProfiles())) {
            is CamouflageIdentity.Active -> identity.profile
            // Nothing enabled, or more than one: the device does not describe an identity, so the
            // default one is re-applied rather than guessed at. A camouflage identity is never
            // invented — the user chose nothing, and a name they did not choose is worse than their
            // own.
            CamouflageIdentity.NeedsRepair -> {
                apply(CamouflageProfile.Default)
                CamouflageProfile.Default
            }
        }
    }

    override suspend fun selectProfile(profile: CamouflageProfile): NivaraResult<Unit> =
        withContext(dispatcher) {
            nivaraRunCatching { apply(profile) }
        }

    /**
     * Which identities the device currently presents as enabled.
     *
     * A component whose state cannot be read counts as not enabled: an unreadable entry is not a
     * presentation Nivara can vouch for, and treating it as disabled leads to the default identity
     * being re-applied, which is the safe direction.
     */
    private fun enabledProfiles(): Set<CamouflageProfile> = CamouflageProfile.entries.filterTo(mutableSetOf()) { profile ->
        isEnabled(componentFor(profile))
    }

    /**
     * Whether one launcher entry is enabled.
     *
     * The platform's answer is three-valued. An explicit enable or disable is taken as it is;
     * `DEFAULT` means the user has never changed this component, so the manifest decides — and the
     * manifest is where Nivara's own entry is enabled and every alias is disabled. The
     * `DISABLED_UNTIL_USED` state Android uses for components that appear once and then withdraw is
     * an absence of a launcher entry, so it counts as disabled.
     */
    private fun isEnabled(component: ComponentName): Boolean = try {
        when (packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            // DEFAULT: the manifest's declared state, which the verifier pins to "Nivara's own entry
            // enabled, every alias disabled".
            else -> component.className == "${context.packageName}.$REAL_ENTRY_COMPONENT"
        }
    } catch (error: Exception) {
        false
    }

    /**
     * Moves the application to [profile]: the entry that carries it first, the others afterwards.
     *
     * `DONT_KILL_APP` is not optional here. The default behaviour of a component change is to kill
     * the application, which would close the configuration screen the user just acted on and, mid
     * transition, could leave the change half-applied with the process gone.
     */
    private fun apply(profile: CamouflageProfile) {
        packageManager.setComponentEnabledSetting(
            componentFor(profile),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        CamouflageProfile.entries
            .filter { candidate -> candidate != profile }
            .forEach { candidate ->
                packageManager.setComponentEnabledSetting(
                    componentFor(candidate),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }

    private companion object {

        /** The application's ordinary entry activity, which carries Nivara's own identity. */
        const val REAL_ENTRY_COMPONENT = "MainActivity"

        /** Prefix of the alias component names declared in the manifest, one per camouflage profile. */
        const val CAMOUFLAGE_ALIAS_PREFIX = "Camouflage"
    }
}
