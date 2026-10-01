package com.nivara.app.ui.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.credential.SecureScreenEffect
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of Nivara's launcher.
 *
 * It creates the view model, observes its state, re-reads everything when the launcher is resumed,
 * and supplies the icon loader from the application container. The screen itself stays stateless.
 *
 * ### The window is protected exactly when hidden applications are on it
 *
 * While a reveal is active the launcher is showing applications the user asked to keep out of
 * sight, so the window is marked sensitive for as long as that is true. The rest of the time Nivara's
 * home surface is an ordinary launcher and gets no such flag — the same effect the credential and
 * management screens use, applied to the window rather than duplicated.
 */
@Composable
fun LauncherRoute(
    onOpenSettings: () -> Unit,
    onOpenHiddenManagement: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LauncherViewModel = viewModel(factory = LauncherViewModel.Factory),
    iconLoader: ApplicationIconLoader = rememberApplicationIconLoader(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    val ready = uiState as? LauncherUiState.Ready
    if (ready?.revealed == true) {
        SecureScreenEffect()
    }

    // The view model reports that a reveal needs a session; the launcher does not authenticate and
    // does not reveal anything by itself. It just asks for the existing credential screen.
    LaunchedEffect(ready?.unlockRequired) {
        if (ready?.unlockRequired == true) {
            viewModel.onUnlockHandled()
            onUnlock()
        }
    }

    LauncherScreen(
        uiState = uiState,
        iconLoader = iconLoader,
        drawerOpen = drawerOpen,
        onDrawerOpenChange = { open -> drawerOpen = open },
        onRetry = viewModel::refresh,
        onQueryChange = viewModel::onQueryChange,
        onSortChange = viewModel::onSortChange,
        onSectionChange = viewModel::onSectionChange,
        onLaunch = viewModel::launch,
        onReveal = viewModel::revealHiddenApplications,
        onConceal = viewModel::concealHiddenApplications,
        onMessageShown = viewModel::onMessageShown,
        onOpenSettings = onOpenSettings,
        onOpenHiddenManagement = onOpenHiddenManagement,
        modifier = modifier,
    )
}

/** The container's icon loader, read the way the view-model factories read the container. */
@Composable
private fun rememberApplicationIconLoader(): ApplicationIconLoader {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as NivaraApplication).container.applicationIconLoader
    }
}

/**
 * Stateless launcher: renders [LauncherUiState] and reports user actions upwards.
 *
 * ### What it shows
 *
 * A home surface — what Nivara is, the way to the app drawer, the way to Nivara's own settings, and
 * the control that reveals hidden applications — plus the drawer itself when it is opened.
 *
 * ### What it does not do
 *
 * It decides nothing. It does not read a repository, does not open an application by itself, does
 * not authenticate, and does not keep hidden applications anywhere. Every action is reported, and
 * the list it draws is the one it was handed.
 *
 * ### The fail-closed states
 *
 * [LauncherUiState.HiddenStateUnreadable] and [LauncherUiState.HiddenStateUnavailable] draw no
 * application at all — not a partial list, not an empty grid with a shrug, and no drawer button.
 * They explain what happened and offer the two things that are safe: try again, and go to Nivara's
 * settings.
 */
@Composable
fun LauncherScreen(
    uiState: LauncherUiState,
    iconLoader: ApplicationIconLoader,
    drawerOpen: Boolean,
    onDrawerOpenChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (ApplicationSortOrder) -> Unit,
    onSectionChange: (LauncherSection) -> Unit,
    onLaunch: (InstalledApplication) -> Unit,
    onReveal: () -> Unit,
    onConceal: () -> Unit,
    onMessageShown: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHiddenManagement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back closes the drawer rather than leaving the launcher, which is what a full-surface drawer
    // should do. It is not a trap: with the drawer closed, Back is not intercepted at all.
    BackHandler(enabled = drawerOpen) { onDrawerOpenChange(false) }

    when (uiState) {
        LauncherUiState.Loading -> NivaraLoadingState(modifier = modifier)

        LauncherUiState.DiscoveryUnavailable -> LauncherUnavailableState(
            titleRes = R.string.launcher_discovery_unavailable_title,
            bodyRes = R.string.launcher_discovery_unavailable,
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )

        LauncherUiState.HiddenStateUnreadable -> LauncherUnavailableState(
            titleRes = R.string.launcher_hidden_unreadable_title,
            bodyRes = R.string.launcher_hidden_unreadable,
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )

        LauncherUiState.HiddenStateUnavailable -> LauncherUnavailableState(
            titleRes = R.string.launcher_hidden_unavailable_title,
            bodyRes = R.string.launcher_hidden_unavailable,
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )

        is LauncherUiState.Ready -> if (drawerOpen) {
            DrawerSurface(
                state = uiState,
                iconLoader = iconLoader,
                onClose = { onDrawerOpenChange(false) },
                onQueryChange = onQueryChange,
                onSortChange = onSortChange,
                onSectionChange = onSectionChange,
                onLaunch = onLaunch,
                onMessageShown = onMessageShown,
                modifier = modifier,
            )
        } else {
            HomeSurface(
                state = uiState,
                onOpenDrawer = { onDrawerOpenChange(true) },
                onReveal = onReveal,
                onConceal = onConceal,
                onMessageShown = onMessageShown,
                onOpenSettings = onOpenSettings,
                onOpenHiddenManagement = onOpenHiddenManagement,
                modifier = modifier,
            )
        }
    }
}

/**
 * The home surface.
 *
 * Three things and nothing else: where the applications are, what Nivara does about hidden ones, and
 * where Nivara's own settings live. The copy is explicit that hiding is a Nivara decision and that
 * Android still shows every application, because a user who reads only this screen must not come
 * away believing otherwise.
 */
@Composable
private fun HomeSurface(
    state: LauncherUiState.Ready,
    onOpenDrawer: () -> Unit,
    onReveal: () -> Unit,
    onConceal: () -> Unit,
    onMessageShown: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHiddenManagement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(id = R.string.launcher_home_summary), style = MaterialTheme.typography.bodyMedium)

        Button(onClick = onOpenDrawer, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.launcher_open_drawer))
        }

        HiddenApplicationsCard(
            state = state,
            onReveal = onReveal,
            onConceal = onConceal,
            onOpenHiddenManagement = onOpenHiddenManagement,
        )

        state.noticeRes?.let { noticeRes ->
            Text(
                text = stringResource(id = noticeRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.dismissibleMessage(onMessageShown),
            )
        }
        state.failure?.let { message ->
            NivaraMessageText(message = message, modifier = Modifier.dismissibleMessage(onMessageShown))
        }

        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.launcher_settings_action))
        }
    }
}

/**
 * Where the user decides whether hidden applications are shown.
 *
 * The control is a labelled button with the count beside it — not a gesture, not a hidden sequence,
 * and not a redesign of Nivara's identity. It is deliberately ordinary, because the only thing
 * protecting hidden applications here is the session, and a user who cannot find the control would
 * be a user who cannot reach their own applications.
 */
@Composable
private fun HiddenApplicationsCard(
    state: LauncherUiState.Ready,
    onReveal: () -> Unit,
    onConceal: () -> Unit,
    onOpenHiddenManagement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(id = R.string.launcher_hidden_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    id = R.string.launcher_hidden_card_summary,
                    state.hiddenCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.launcher_hidden_card_note),
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.revealed) {
                OutlinedButton(onClick = onConceal, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.launcher_conceal_action))
                }
            } else {
                Button(
                    onClick = onReveal,
                    enabled = state.canReveal && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (state.sessionAuthenticated) {
                            stringResource(id = R.string.launcher_reveal_action)
                        } else {
                            // No session: the same button, and the screen that answers it is the
                            // existing credential screen. Nothing is revealed without one.
                            stringResource(id = R.string.launcher_unlock_action)
                        },
                    )
                }
            }

            OutlinedButton(onClick = onOpenHiddenManagement, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.launcher_manage_action))
            }
        }
    }
}

/** The drawer as a full surface, with a way back that is not a gesture. */
@Composable
private fun DrawerSurface(
    state: LauncherUiState.Ready,
    iconLoader: ApplicationIconLoader,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (ApplicationSortOrder) -> Unit,
    onSectionChange: (LauncherSection) -> Unit,
    onLaunch: (InstalledApplication) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_back),
                    contentDescription = stringResource(id = R.string.launcher_drawer_close),
                )
            }
            Text(
                text = stringResource(id = R.string.launcher_drawer_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        state.failure?.let { message ->
            NivaraMessageText(
                message = message,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .dismissibleMessage(onMessageShown),
            )
        }

        AppDrawer(
            state = state,
            iconLoader = iconLoader,
            onQueryChange = onQueryChange,
            onSortChange = onSortChange,
            onSectionChange = onSectionChange,
            onLaunch = onLaunch,
        )
    }
}

/**
 * A state in which Nivara has nothing safe to draw.
 *
 * Used for the three situations that are not "here are your applications": discovery failed, the
 * stored hidden set cannot be decoded, and the stored hidden set cannot be reached. Each says what
 * happened in its own words, and offers only the two actions that are safe — try again, or go to
 * Nivara's settings — so no application is drawn on the strength of an answer Nivara does not have.
 */
@Composable
private fun LauncherUnavailableState(
    titleRes: Int,
    bodyRes: Int,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(id = titleRes),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(id = bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.state_retry_action))
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.launcher_settings_action))
        }
    }
}

/**
 * Makes a message tappable so it can be dismissed.
 *
 * A launcher's surface has no dismiss button of its own, and a failure notice that cannot be cleared
 * would sit on the home screen until something else happened to replace it.
 */
private fun Modifier.dismissibleMessage(onDismiss: () -> Unit): Modifier =
    this
        .padding(top = 4.dp)
        .clickable(onClick = onDismiss)

@Preview(name = "Launcher – home", showBackground = true)
@Composable
private fun LauncherHomePreview() {
    NivaraTheme {
        LauncherScreen(
            uiState = previewState(),
            iconLoader = ApplicationIconLoader { null },
            drawerOpen = false,
            onDrawerOpenChange = {},
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onLaunch = {},
            onReveal = {},
            onConceal = {},
            onMessageShown = {},
            onOpenSettings = {},
            onOpenHiddenManagement = {},
        )
    }
}

@Preview(name = "Launcher – drawer", showBackground = true)
@Composable
private fun LauncherDrawerPreview() {
    NivaraTheme {
        LauncherScreen(
            uiState = previewState(),
            iconLoader = ApplicationIconLoader { null },
            drawerOpen = true,
            onDrawerOpenChange = {},
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onLaunch = {},
            onReveal = {},
            onConceal = {},
            onMessageShown = {},
            onOpenSettings = {},
            onOpenHiddenManagement = {},
        )
    }
}

@Preview(name = "Launcher – hidden state unreadable", showBackground = true)
@Composable
private fun LauncherHiddenUnreadablePreview() {
    NivaraTheme {
        LauncherScreen(
            uiState = LauncherUiState.HiddenStateUnreadable,
            iconLoader = ApplicationIconLoader { null },
            drawerOpen = false,
            onDrawerOpenChange = {},
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onLaunch = {},
            onReveal = {},
            onConceal = {},
            onMessageShown = {},
            onOpenSettings = {},
            onOpenHiddenManagement = {},
        )
    }
}

/** A ready state with a few applications, for previews only. */
private fun previewState(): LauncherUiState.Ready = LauncherUiState.Ready(
    entries = listOf(
        InstalledApplication("com.example.camera", "Camera"),
        InstalledApplication("com.example.notes", "Notes"),
        InstalledApplication("com.example.maps", "Maps"),
    ),
    section = LauncherSection.All,
    sort = ApplicationSortOrder.NameAscending,
    query = "",
    revealed = false,
    sessionAuthenticated = true,
    discoveredCount = 4,
    hiddenCount = 1,
    withheldCount = 1,
)
