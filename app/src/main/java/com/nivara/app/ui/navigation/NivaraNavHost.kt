package com.nivara.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nivara.app.ui.about.AboutRoute
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
    }
}

/** Navigates to [destination], collapsing duplicate requests for the same screen. */
private fun NavHostController.navigateTo(destination: NivaraDestination) {
    navigate(destination.route) { launchSingleTop = true }
}
