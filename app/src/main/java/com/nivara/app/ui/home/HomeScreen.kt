package com.nivara.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.security.SessionState
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.NivaraApplicationIcon
import com.nivara.app.ui.applock.ProtectionRunState
import com.nivara.app.ui.applock.management.AppLockManagementUiState
import com.nivara.app.ui.applock.management.AppLockManagementViewModel
import com.nivara.app.ui.applock.management.ApplicationSection
import com.nivara.app.ui.applock.management.ManagedApplication
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.ui.credential.SecureScreenEffect
import com.nivara.app.ui.theme.NivaraColors
import com.nivara.app.ui.theme.nivaraHeaderGradientColors

/**
 * Stateful entry point of the home screen.
 *
 * The home is the product's front door: a blue header with the four main rooms — Vault, App
 * Lock, Hide apps and everything else — a one-line security check, and the application list
 * with its lock toggles, exactly like the management screen but without its power tools. The
 * power tools (search ordering, preparation, counts) stay one tap away on the App Lock screen.
 */
@Composable
fun HomeRoute(
    onOpenVault: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onOpenAppLock: () -> Unit,
    onOpenAllFeatures: () -> Unit,
    onOpenUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
    lockViewModel: AppLockManagementViewModel = viewModel(factory = AppLockManagementViewModel.Factory),
    iconLoader: ApplicationIconLoader = rememberHomeIconLoader(),
) {
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val lockState by lockViewModel.uiState.collectAsStateWithLifecycle()

    // The home lists the device's applications, which Android treats as personal data, so the
    // same window flag every other application list uses keeps it out of screenshots and the
    // recents thumbnail.
    SecureScreenEffect()

    // A return from Android's settings, from the credential screen or from a grant in the
    // onboarding gate is noticed here: everything visible is re-read on every resume.
    LifecycleResumeEffect(Unit) {
        homeViewModel.refresh()
        lockViewModel.onResumed()
        onPauseOrDispose { }
    }

    HomeScreen(
        homeState = homeState,
        lockState = lockState,
        iconLoader = iconLoader,
        onScan = {
            homeViewModel.refresh()
            lockViewModel.refresh()
        },
        onSectionChange = lockViewModel::onSectionChange,
        onQueryChange = lockViewModel::onQueryChange,
        onProtect = lockViewModel::protect,
        onUnprotect = lockViewModel::unprotect,
        onOpenVault = onOpenVault,
        onOpenHiddenApps = onOpenHiddenApps,
        onOpenCamouflage = onOpenCamouflage,
        onOpenAppLock = onOpenAppLock,
        onOpenAllFeatures = onOpenAllFeatures,
        onOpenUnlock = onOpenUnlock,
        modifier = modifier,
    )
}

/** The container's icon loader, read the way the view-model factories read the container. */
@Composable
private fun rememberHomeIconLoader(): ApplicationIconLoader {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        (context.applicationContext as NivaraApplication).container.applicationIconLoader
    }
}

/**
 * Stateless home screen: the blue header with its tiles and security check, then the white
 * sheet with the Unlocked/Locked list.
 */
@Composable
fun HomeScreen(
    homeState: HomeUiState,
    lockState: AppLockManagementUiState,
    iconLoader: ApplicationIconLoader,
    onScan: () -> Unit,
    onSectionChange: (ApplicationSection) -> Unit,
    onQueryChange: (String) -> Unit,
    onProtect: (InstalledApplication) -> Unit,
    onUnprotect: (InstalledApplication) -> Unit,
    onOpenVault: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onOpenAppLock: () -> Unit,
    onOpenAllFeatures: () -> Unit,
    onOpenUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (gradientStart, gradientEnd) = nivaraHeaderGradientColors()
    var searchOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(listOf(gradientStart, gradientEnd))),
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenCamouflage) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_hat),
                        contentDescription = stringResource(id = R.string.home_cd_camouflage),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onOpenHiddenApps) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_eye_off),
                        contentDescription = stringResource(id = R.string.home_cd_hide_apps),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onOpenAllFeatures) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_settings),
                        contentDescription = stringResource(id = R.string.home_cd_all_features),
                        tint = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(NivaraSpacing.row))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                HomeTile(
                    iconRes = R.drawable.ic_vault,
                    label = stringResource(id = R.string.home_tile_vault),
                    gradient = listOf(NivaraColors.TileVaultStart, NivaraColors.TileVaultEnd),
                    onClick = onOpenVault,
                )
                HomeTile(
                    iconRes = R.drawable.ic_lock,
                    label = stringResource(id = R.string.home_tile_applock),
                    gradient = listOf(NivaraColors.TileAppLockStart, NivaraColors.TileAppLockEnd),
                    onClick = onOpenAppLock,
                )
                HomeTile(
                    iconRes = R.drawable.ic_eye_off,
                    label = stringResource(id = R.string.home_tile_hide),
                    gradient = listOf(NivaraColors.TileHideStart, NivaraColors.TileHideEnd),
                    onClick = onOpenHiddenApps,
                )
                HomeTile(
                    iconRes = R.drawable.ic_apps_grid,
                    label = stringResource(id = R.string.home_tile_all),
                    gradient = listOf(NivaraColors.TileAllStart, NivaraColors.TileAllEnd),
                    onClick = onOpenAllFeatures,
                )
            }

            Spacer(modifier = Modifier.height(NivaraSpacing.row))

            SecurityCheckCard(
                homeState = homeState,
                lockState = lockState,
                onScan = onScan,
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (lockState) {
                AppLockManagementUiState.Loading -> HomeSheetLoading()
                AppLockManagementUiState.Error -> HomeSheetLoading()
                is AppLockManagementUiState.Ready -> HomeSheet(
                    state = lockState,
                    session = (homeState as? HomeUiState.Ready)?.session,
                    iconLoader = iconLoader,
                    searchOpen = searchOpen,
                    onToggleSearch = { searchOpen = !searchOpen },
                    onSectionChange = onSectionChange,
                    onQueryChange = onQueryChange,
                    onProtect = onProtect,
                    onUnprotect = onUnprotect,
                    onOpenUnlock = onOpenUnlock,
                )
            }
        }
    }
}

/** One rounded, gradient tile of the header row. */
@Composable
private fun HomeTile(
    iconRes: Int,
    label: String,
    gradient: List<Color>,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clickable(onClick = onClick)
                .background(
                    brush = Brush.linearGradient(gradient),
                    shape = RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.height(NivaraSpacing.tight))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

/**
 * The one-line security check: protection run state, credential and device lock, re-read by the
 * Check button. It states only what was read, and offers the App Lock screen for the details.
 */
@Composable
private fun SecurityCheckCard(
    homeState: HomeUiState,
    lockState: AppLockManagementUiState,
    onScan: () -> Unit,
) {
    val summary = when {
        homeState is HomeUiState.Ready && homeState.credentialType == null ->
            stringResource(id = R.string.home_scan_no_credential)
        lockState is AppLockManagementUiState.Ready &&
            lockState.runState == ProtectionRunState.Running ->
            stringResource(id = R.string.home_scan_running)
        else -> stringResource(id = R.string.home_scan_stopped)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier.padding(NivaraSpacing.row),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = Color.White.copy(alpha = 0.2f), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_shield_check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.home_scan_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(50),
            ) {
                Text(text = stringResource(id = R.string.home_scan_action))
            }
        }
    }
}

@Composable
private fun HomeSheetLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

/** The white sheet: Unlocked/Locked tabs, optional search, and the lock-toggle list. */
@Composable
private fun HomeSheet(
    state: AppLockManagementUiState.Ready,
    session: SessionState?,
    iconLoader: ApplicationIconLoader,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onSectionChange: (ApplicationSection) -> Unit,
    onQueryChange: (String) -> Unit,
    onProtect: (InstalledApplication) -> Unit,
    onUnprotect: (InstalledApplication) -> Unit,
    onOpenUnlock: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTab(
                label = stringResource(id = R.string.home_sheet_unlocked),
                selected = state.section == ApplicationSection.All,
                onClick = { onSectionChange(ApplicationSection.All) },
            )
            Spacer(modifier = Modifier.width(NivaraSpacing.section))
            SheetTab(
                label = stringResource(id = R.string.home_sheet_locked),
                selected = state.section == ApplicationSection.Protected,
                onClick = { onSectionChange(ApplicationSection.Protected) },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleSearch) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = stringResource(id = R.string.applock_manage_search_label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NivaraSpacing.screen),
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.applock_manage_search_label)) },
            )
        }

        if (session != null && !session.isAuthenticated) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.small),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(NivaraSpacing.row),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.home_unlock_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(id = R.string.home_unlock_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onOpenUnlock) {
                        Text(text = stringResource(id = R.string.home_unlock_action))
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = NivaraSpacing.screen,
                vertical = NivaraSpacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight),
        ) {
            items(state.rows, key = { it.packageName }) { row ->
                HomeAppRow(
                    row = row,
                    iconLoader = iconLoader,
                    canChange = state.sessionAuthenticated && !state.storedSetUnreadable && !state.busy,
                    onProtect = onProtect,
                    onUnprotect = onUnprotect,
                )
            }
        }
    }
}

@Composable
private fun SheetTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(NivaraSpacing.small),
        )
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(40.dp)
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(50),
                ),
        )
    }
}

/** One application row: icon, label, and the lock toggle for it. */
@Composable
private fun HomeAppRow(
    row: ManagedApplication,
    iconLoader: ApplicationIconLoader,
    canChange: Boolean,
    onProtect: (InstalledApplication) -> Unit,
    onUnprotect: (InstalledApplication) -> Unit,
) {
    var icon by remember(row.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(row.packageName, iconLoader) {
        icon = iconLoader.iconFor(row.packageName)
    }
    val protected = row.state != null &&
        row.state != com.nivara.app.domain.applock.ApplicationProtectionState.NotProtected

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NivaraSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        NivaraApplicationIcon(icon = icon)
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { if (protected) onUnprotect(row.application) else onProtect(row.application) },
            enabled = canChange && row.state != null,
        ) {
            Icon(
                painter = painterResource(
                    id = if (protected) R.drawable.ic_lock else R.drawable.ic_lock_open,
                ),
                contentDescription = stringResource(
                    id = if (protected) R.string.home_lock_cd_unlock else R.string.home_lock_cd_lock,
                ),
                tint = if (protected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
        }
    }
}
