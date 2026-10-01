package com.nivara.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nivara.app.R
import com.nivara.app.ui.navigation.NivaraDestination
import com.nivara.app.ui.navigation.NivaraNavHost

/**
 * Root composable of the application: app bar plus navigation host.
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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = NivaraDestination.fromRoute(backStackEntry?.destination?.route)
    val canNavigateUp = navController.previousBackStackEntry != null
    val showAppBar = destination != NivaraDestination.Launcher

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
