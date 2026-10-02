package com.nivara.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nivara.app.R
import com.nivara.app.ui.navigation.NivaraDestination
import com.nivara.app.ui.navigation.NivaraNavHost
import com.nivara.app.ui.onboarding.OnboardingScreen
import com.nivara.app.ui.onboarding.OnboardingViewModel

/**
 * Root composable of the application: the permission gate, then app bar plus navigation host.
 *
 * The gate stands in front of the application's own task only: until Usage Access, overlay and
 * the battery exemption are all granted, the onboarding screen is the whole application, and it
 * is re-checked on every return to the foreground — so a grant made in Android's settings is
 * noticed the moment the user comes back, and once everything is granted the gate never appears
 * again. The Home-application task is never gated: it is the device's home screen, and trapping
 * it would trap the phone.
 *
 * The app bar is owned here rather than by each screen so that titles, insets and the back
 * affordance stay consistent while the number of screens grows.
 *
 * [startDestination] is passed through to the graph, which lets the Home application start at
 * [NivaraDestination.Launcher] while the application's own task starts at [NivaraDestination.Home].
 * The launcher destination draws its own surface — a home screen with an app bar above it would be
 * neither — so the bar is omitted for that one destination and shown everywhere else, including the
 * Nivara screens the launcher navigates to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NivaraApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = NivaraDestination.Home.route,
) {
    val onboardingViewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory)
    val onboardingState by onboardingViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onboardingViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (startDestination == NivaraDestination.Home.route && !onboardingState.requiredGranted) {
        OnboardingScreen(
            state = onboardingState,
            onOpenUsage = onboardingViewModel::openUsageSettings,
            onOpenOverlay = onboardingViewModel::openOverlaySettings,
            onRequestBattery = onboardingViewModel::requestBatteryExemption,
            onRefresh = onboardingViewModel::refresh,
            modifier = modifier,
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = NivaraDestination.fromRoute(backStackEntry?.destination?.route)
    val canNavigateUp = navController.previousBackStackEntry != null
    // Home draws its own blue header with the product name, and the launcher draws its own
    // surface; both omit the shared bar, everything else keeps it.
    val showAppBar = destination != NivaraDestination.Launcher && destination != NivaraDestination.Home

    Scaffold(
        modifier = modifier,
        topBar = {
            if (showAppBar) {
                TopAppBar(
                    title = {
                        Text(text = stringResource(id = destination?.titleRes ?: R.string.app_name))
                    },
                    navigationIcon = {
                        if (canNavigateUp) {
                            IconButton(onClick = { navController.navigateUp() }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_back),
                                    contentDescription = stringResource(id = R.string.navigation_back),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NivaraNavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
