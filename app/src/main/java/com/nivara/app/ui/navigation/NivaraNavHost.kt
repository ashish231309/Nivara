package com.nivara.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nivara.app.ui.about.AboutRoute
import com.nivara.app.ui.applock.AppLockSetupRoute
import com.nivara.app.ui.apphide.management.HiddenManagementRoute
import com.nivara.app.ui.applock.management.AppLockManagementRoute
import com.nivara.app.ui.biometric.BiometricRoute
import com.nivara.app.ui.credential.CredentialChangeRoute
import com.nivara.app.ui.credential.CredentialSetupRoute
import com.nivara.app.ui.credential.CredentialVerifyRoute
import com.nivara.app.ui.home.HomeRoute

/**
 * Navigation graph of the application.
 *
 * One entry per destination, mirroring [NivaraDestination.entries]. Screens are reached
 * through their `…Route` composables, which own state creation; the screens themselves stay
 * stateless and previewable.
 */
@Composable
fun NivaraNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = NivaraDestination.Home.route,
        modifier = modifier,
    ) {
        composable(route = NivaraDestination.Home.route) {
            HomeRoute(
                onOpenAbout = { navController.navigateTo(NivaraDestination.About) },
                onOpenCredentialSetup = { navController.navigateTo(NivaraDestination.CredentialSetup) },
                onOpenCredentialVerify = { navController.navigateTo(NivaraDestination.CredentialVerify) },
                onOpenCredentialChange = { navController.navigateTo(NivaraDestination.CredentialChange) },
                onOpenBiometric = { navController.navigateTo(NivaraDestination.Biometric) },
                onOpenAppLock = { navController.navigateTo(NivaraDestination.AppLock) },
                onOpenHiddenApps = { navController.navigateTo(NivaraDestination.HiddenApps) },
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
