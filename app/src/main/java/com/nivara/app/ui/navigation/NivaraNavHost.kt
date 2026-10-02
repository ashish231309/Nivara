package com.nivara.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nivara.app.ui.components.NivaraMotion
import com.nivara.app.ui.components.rememberNivaraMotionScale
import com.nivara.app.ui.components.scaledDurationMillis
import com.nivara.app.ui.about.AboutRoute
import com.nivara.app.ui.launcher.LauncherRoute
import com.nivara.app.ui.applock.AppLockSetupRoute
import com.nivara.app.ui.apphide.management.HiddenManagementRoute
import com.nivara.app.ui.applock.management.AppLockManagementRoute
import com.nivara.app.ui.biometric.BiometricRoute
import com.nivara.app.ui.camouflage.CamouflageRoute
import com.nivara.app.ui.credential.CredentialChangeRoute
import com.nivara.app.ui.credential.CredentialSetupRoute
import com.nivara.app.ui.credential.CredentialVerifyRoute
import com.nivara.app.ui.home.HomeRoute
import com.nivara.app.ui.vault.VaultRoute
import com.nivara.app.ui.vault.recovery.VaultRecoveryRoute

/**
 * Navigation graph of the application.
 *
 * One entry per destination, mirroring [NivaraDestination.entries]. Screens are reached
 * through their `…Route` composables, which own state creation; the screens themselves stay
 * stateless and previewable.
 *
 * [startDestination] exists because Nivara has two entry points into one graph: the application's
 * own task starts at [NivaraDestination.Home], and the Home application starts at
 * [NivaraDestination.Launcher]. Everything else — every settings screen, every route, the whole
 * back stack — is shared, so there is one graph and not two.
 */
@Composable
fun NivaraNavHost(
    navController: NavHostController,
    startDestination: String = NivaraDestination.Home.route,
    modifier: Modifier = Modifier,
) {
    // One read of the device's animator setting, applied to every transition in the graph: a
    // person who asked the platform for less motion gets the same destinations with none of the
    // movement. A zero duration makes each transition resolve to its end state immediately.
    val motionScale = rememberNivaraMotionScale()
    val enterMillis = scaledDurationMillis(NivaraMotion.STANDARD_MILLIS, motionScale)
    val exitMillis = scaledDurationMillis(NivaraMotion.QUICK_MILLIS, motionScale)

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(enterMillis)) +
                slideInHorizontally(animationSpec = tween(enterMillis)) { width -> width / 16 }
        },
        exitTransition = { fadeOut(animationSpec = tween(exitMillis)) },
        popEnterTransition = {
            fadeIn(animationSpec = tween(enterMillis)) +
                slideInHorizontally(animationSpec = tween(enterMillis)) { width -> -width / 16 }
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(exitMillis)) +
                slideOutHorizontally(animationSpec = tween(exitMillis)) { width -> width / 16 }
        },
    ) {
        composable(route = NivaraDestination.Launcher.route) {
            LauncherRoute(
                // Nivara's own screens are the existing ones: the settings home, the hidden-application
                // screen and the credential screen. The launcher reimplements none of them, and
                // authenticates nobody itself.
                onOpenSettings = { navController.navigateTo(NivaraDestination.Home) },
                onOpenHiddenManagement = { navController.navigateTo(NivaraDestination.HiddenApps) },
                onUnlock = { navController.navigateTo(NivaraDestination.CredentialVerify) },
            )
        }
        composable(route = NivaraDestination.Home.route) {
            HomeRoute(
                onOpenAbout = { navController.navigateTo(NivaraDestination.About) },
                onOpenCredentialSetup = { navController.navigateTo(NivaraDestination.CredentialSetup) },
                onOpenCredentialVerify = { navController.navigateTo(NivaraDestination.CredentialVerify) },
                onOpenCredentialChange = { navController.navigateTo(NivaraDestination.CredentialChange) },
                onOpenBiometric = { navController.navigateTo(NivaraDestination.Biometric) },
                onOpenAppLock = { navController.navigateTo(NivaraDestination.AppLock) },
                onOpenHiddenApps = { navController.navigateTo(NivaraDestination.HiddenApps) },
                onOpenCamouflage = { navController.navigateTo(NivaraDestination.Camouflage) },
                onOpenVault = { navController.navigateTo(NivaraDestination.Vault) },
            )
        }
        composable(route = NivaraDestination.About.route) {
            AboutRoute()
        }
        composable(route = NivaraDestination.CredentialSetup.route) {
            CredentialSetupRoute(onDone = { navController.popBackStack() })
        }
        composable(route = NivaraDestination.CredentialVerify.route) {
            CredentialVerifyRoute(
                onChangeCredential = { navController.navigateTo(NivaraDestination.CredentialChange) },
            )
        }
        composable(route = NivaraDestination.CredentialChange.route) {
            CredentialChangeRoute(onDone = { navController.popBackStack() })
        }
        composable(route = NivaraDestination.Biometric.route) {
            BiometricRoute(
                onOpenCredentialSetup = { navController.navigateTo(NivaraDestination.CredentialSetup) },
            )
        }
        composable(route = NivaraDestination.AppLockSetup.route) {
            AppLockSetupRoute()
        }
        composable(route = NivaraDestination.HiddenApps.route) {
            HiddenManagementRoute(
                // The credential screen is the one place a session is opened. Hiding does not
                // reimplement it, and does not keep a session of its own.
                onUnlock = { navController.navigateTo(NivaraDestination.CredentialVerify) },
            )
        }
        composable(route = NivaraDestination.Camouflage.route) {
            CamouflageRoute(
                // The credential screen is the one place a session is opened. Changing the
                // application's own identity does not reimplement it, and does not keep a session of
                // its own.
                onUnlock = { navController.navigateTo(NivaraDestination.CredentialVerify) },
            )
        }
        composable(route = NivaraDestination.Vault.route) {
            VaultRoute(
                // The credential screen is the one place a session is opened. Creating a vault needs
                // one, and the vault screen does not reimplement authentication to get it: it sends
                // the user to the same screen every other configuration change uses.
                onUnlock = { navController.navigateTo(NivaraDestination.CredentialVerify) },
                onRecover = { navController.navigateTo(NivaraDestination.VaultRecovery) },
            )
        }
        composable(route = NivaraDestination.VaultRecovery.route) {
            VaultRecoveryRoute(
                // A successful reconnection lands back on the vault screen, which re-reads the
                // vault on resume and shows it as its own. Recovery grants possession of the vault
                // key, and that is not a session: the usual unlock still stands between the user
                // and the vault's contents.
                onRecovered = { navController.popBackStack() },
            )
        }
        composable(route = NivaraDestination.AppLock.route) {
            AppLockManagementRoute(
                // Preparation is where a missing capability is explained and changed, and the
                // credential screen is the one place a session is opened. Neither is reimplemented
                // here: both are the existing screens.
                onOpenPreparation = { navController.navigateTo(NivaraDestination.AppLockSetup) },
                onUnlock = { navController.navigateTo(NivaraDestination.CredentialVerify) },
            )
        }
    }
}

/** Navigates to [destination], collapsing duplicate requests for the same screen. */
private fun NavHostController.navigateTo(destination: NivaraDestination) {
    navigate(destination.route) { launchSingleTop = true }
}
