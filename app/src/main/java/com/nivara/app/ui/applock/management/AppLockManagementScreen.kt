package com.nivara.app.ui.applock.management

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.applock.ApplicationProtectionState
import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.applications.NivaraApplicationIcon
import com.nivara.app.ui.applock.ProtectionRunState
import com.nivara.app.ui.applock.prerequisiteNameRes
import com.nivara.app.ui.components.NivaraErrorState
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.credential.SecureScreenEffect
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the App Lock management screen.
 *
 * It creates the view model, observes its state, re-reads everything when the screen is resumed —
 * which is how a return from Android's own settings is noticed — and supplies the icon loader from
 * the application container. The screen itself stays stateless.
 */
@Composable
fun AppLockManagementRoute(
    onOpenPreparation: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppLockManagementViewModel = viewModel(factory = AppLockManagementViewModel.Factory),
    iconLoader: ApplicationIconLoader = rememberApplicationIconLoader(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The screen lists the device's applications and says which of them the user protects. Android
    // treats the installed-application list as personal data, so the same window flag the
    // preparation and credential screens use keeps it out of screenshots, recordings and the
    // recents thumbnail. There is one implementation of it, and this is not another one.
    SecureScreenEffect()

    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    AppLockManagementScreen(
        uiState = uiState,
        iconLoader = iconLoader,
        onRetry = viewModel::refresh,
        onQueryChange = viewModel::onQueryChange,
        onSortChange = viewModel::onSortChange,
        onSectionChange = viewModel::onSectionChange,
        onProtect = viewModel::protect,
        onUnprotect = viewModel::unprotect,
        onOpenPreparation = onOpenPreparation,
        onUnlock = onUnlock,
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
 * Stateless App Lock management screen: renders [AppLockManagementUiState] and reports user actions
 * upwards.
 *
 * ### What it shows
 *
 * The applications the device can launch, what the stored protected set says about each of them,
 * whether the capabilities App Lock needs are in place, and whether protection is running. The
 * three readiness answers are deliberately separate lines: a missing Usage Access grant, a missing
 * overlay grant, and a protection component that is not running are different problems with
 * different remedies, and the screen says which one applies instead of showing a single green or
 * red state.
 *
 * ### What it does not do
 *
 * It never decides anything: the rows are what the view model read, and a tap reports the intent.
 * It does not name a package anywhere — the package name is an identity the code keeps, not
 * something a person needs to read — and it shows no usage information, because none is collected.
 */
@Composable
fun AppLockManagementScreen(
    uiState: AppLockManagementUiState,
    iconLoader: ApplicationIconLoader,
    onRetry: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (ApplicationSortOrder) -> Unit,
    onSectionChange: (ApplicationSection) -> Unit,
    onProtect: (InstalledApplication) -> Unit,
    onUnprotect: (InstalledApplication) -> Unit,
    onOpenPreparation: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        AppLockManagementUiState.Loading -> NivaraLoadingState(modifier = modifier)
        AppLockManagementUiState.Error -> NivaraErrorState(onRetry = onRetry, modifier = modifier)
        is AppLockManagementUiState.Ready -> AppLockManagementContent(
            state = uiState,
            iconLoader = iconLoader,
            onQueryChange = onQueryChange,
            onSortChange = onSortChange,
            onSectionChange = onSectionChange,
            onProtect = onProtect,
            onUnprotect = onUnprotect,
            onOpenPreparation = onOpenPreparation,
            onUnlock = onUnlock,
            modifier = modifier,
        )
    }
}

@Composable
private fun AppLockManagementContent(
    state: AppLockManagementUiState.Ready,
    iconLoader: ApplicationIconLoader,
    onQueryChange: (String) -> Unit,
    onSortChange: (ApplicationSortOrder) -> Unit,
    onSectionChange: (ApplicationSection) -> Unit,
    onProtect: (InstalledApplication) -> Unit,
    onUnprotect: (InstalledApplication) -> Unit,
    onOpenPreparation: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canChange = state.sessionAuthenticated && !state.storedSetUnreadable && !state.busy

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        ReadinessCard(
            state = state,
            onOpenPreparation = onOpenPreparation,
        )

        if (!state.sessionAuthenticated) {
            LockedCard(onUnlock = onUnlock)
        }

        state.noticeRes?.let { noticeRes ->
            Text(
                text = stringResource(id = noticeRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.failure?.let { message -> NivaraMessageText(message = message) }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = stringResource(id = R.string.applock_manage_search_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        SectionSelector(
            section = state.section,
            onSectionChange = onSectionChange,
        )
        SortSelector(
            sort = state.sort,
            onSortChange = onSortChange,
        )

        ApplicationList(
            state = state,
            iconLoader = iconLoader,
            canChange = canChange,
            onProtect = onProtect,
            onUnprotect = onUnprotect,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The readiness card: what App Lock can do, what it is missing, and whether protection is running.
 *
 * The run state is taken from the component that owns protection rather than assumed, and a missing
 * capability is named with the same copy the preparation screen uses. Nothing here says that an
 * application is protected when the means to protect it are absent.
 */
@Composable
private fun ReadinessCard(
    state: AppLockManagementUiState.Ready,
    onOpenPreparation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runStateRes = when (state.runState) {
        ProtectionRunState.Stopped -> R.string.applock_setup_protection_stopped
        ProtectionRunState.Running -> R.string.applock_setup_protection_running
        ProtectionRunState.WithoutDecision -> R.string.applock_setup_protection_unavailable
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(NivaraSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight),
        ) {
            Text(
                text = stringResource(id = R.string.applock_manage_summary),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.capabilitiesReady) {
                Text(
                    text = stringResource(id = R.string.applock_manage_ready),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // One line per missing capability, named with the same copy the preparation screen
                // uses, so "what is missing" is never summarised into a single vague sentence.
                Text(
                    text = stringResource(id = R.string.applock_manage_missing),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.missingPrerequisites.forEach { prerequisite ->
                    Text(
                        text = stringResource(
                            id = R.string.applock_manage_missing_item,
                            stringResource(id = prerequisiteNameRes(prerequisite)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                text = stringResource(id = runStateRes),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (state.storedSetUnreadable) {
                    stringResource(id = R.string.applock_manage_protected_set_unreadable)
                } else {
                    stringResource(
                        id = R.string.applock_manage_counts,
                        state.protectedCount,
                        state.discoveredCount,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.protectedNotInstalledCount > 0) {
                Text(
                    text = stringResource(
                        id = R.string.applock_manage_not_installed,
                        state.protectedNotInstalledCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onOpenPreparation) {
                Text(text = stringResource(id = R.string.applock_manage_open_preparation))
            }
        }
    }
}

/**
 * Shown while the session gate is closed.
 *
 * A change is refused in that state, so the screen says so before the user taps anything, and
 * offers the one way to open the gate: the existing credential screen. Nothing here authenticates
 * anybody, and nothing here keeps a session of its own.
 */
@Composable
private fun LockedCard(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        Text(
            text = stringResource(id = R.string.applock_manage_locked),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.applock_manage_unlock_action))
        }
    }
}

/**
 * All applications, or only the protected ones.
 *
 * A radio group rather than a chip row: the two options are exclusive, and a radio group gives
 * accessibility services the state of each option without any extra work.
 */
@Composable
private fun SectionSelector(
    section: ApplicationSection,
    onSectionChange: (ApplicationSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight)) {
        Text(
            text = stringResource(id = R.string.applock_manage_section_label),
            style = MaterialTheme.typography.labelLarge,
        )
        SelectableOption(
            labelRes = R.string.applock_manage_section_all,
            selected = section == ApplicationSection.All,
            onSelect = { onSectionChange(ApplicationSection.All) },
        )
        SelectableOption(
            labelRes = R.string.applock_manage_section_protected,
            selected = section == ApplicationSection.Protected,
            onSelect = { onSectionChange(ApplicationSection.Protected) },
        )
    }
}

/** The ordering control. Both directions come from the domain's own comparator. */
@Composable
private fun SortSelector(
    sort: ApplicationSortOrder,
    onSortChange: (ApplicationSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight)) {
        Text(
            text = stringResource(id = R.string.applock_manage_sort_label),
            style = MaterialTheme.typography.labelLarge,
        )
        SelectableOption(
            labelRes = R.string.applock_manage_sort_name_ascending,
            selected = sort == ApplicationSortOrder.NameAscending,
            onSelect = { onSortChange(ApplicationSortOrder.NameAscending) },
        )
        SelectableOption(
            labelRes = R.string.applock_manage_sort_name_descending,
            selected = sort == ApplicationSortOrder.NameDescending,
            onSelect = { onSortChange(ApplicationSortOrder.NameDescending) },
        )
    }
}

/** One option of an exclusive group: the whole row is the target, which is what a screen reader expects. */
@Composable
private fun SelectableOption(
    @StringRes labelRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label is the semantics; the radio button itself is decoration inside the row.
        RadioButton(selected = selected, onClick = null)
        Text(
            text = stringResource(id = labelRes),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The list itself, or the sentence that explains why there is nothing to show. */
@Composable
private fun ApplicationList(
    state: AppLockManagementUiState.Ready,
    iconLoader: ApplicationIconLoader,
    canChange: Boolean,
    onProtect: (InstalledApplication) -> Unit,
    onUnprotect: (InstalledApplication) -> Unit,
    modifier: Modifier = Modifier,
) {
    val emptyMessageRes = when (state.emptiness) {
        AppLockListEmptiness.DeviceHasNoApplications -> R.string.applock_manage_empty_device
        AppLockListEmptiness.NoSearchResults -> R.string.applock_manage_empty_search
        AppLockListEmptiness.NothingProtected -> R.string.applock_manage_empty_protected
        AppLockListEmptiness.ProtectedSetUnreadable -> R.string.applock_manage_empty_unreadable
        null -> null
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items = state.rows, key = { row -> row.packageName }) { row ->
            ApplicationRow(
                row = row,
                iconLoader = iconLoader,
                canChange = canChange,
                onProtect = { onProtect(row.application) },
                onUnprotect = { onUnprotect(row.application) },
            )
        }
        if (state.rows.isEmpty() && emptyMessageRes != null) {
            item(key = "empty") {
                Text(
                    text = stringResource(id = emptyMessageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = NivaraSpacing.screen),
                )
            }
        }
    }
}

/**
 * One application: its icon, its label, what Nivara currently says about protecting it, and the
 * control that changes that.
 *
 * The protection state is written out in words as well as shown on the button, so nothing depends on
 * colour alone. When the stored set could not be read the row says so and the control is disabled:
 * a tick box that cannot be trusted is worse than none.
 */
@Composable
private fun ApplicationRow(
    row: ManagedApplication,
    iconLoader: ApplicationIconLoader,
    canChange: Boolean,
    onProtect: () -> Unit,
    onUnprotect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = rememberApplicationIcon(iconLoader, row.packageName)
    val stateRes = when (row.state) {
        ApplicationProtectionState.Protected -> R.string.applock_manage_state_protected
        ApplicationProtectionState.NotProtected -> R.string.applock_manage_state_not_protected
        ApplicationProtectionState.ProtectedButUnavailable -> R.string.applock_manage_state_unavailable
        null -> R.string.applock_manage_state_unknown
    }
    val inStoredSet = row.state == ApplicationProtectionState.Protected ||
        row.state == ApplicationProtectionState.ProtectedButUnavailable
    val actionLabelRes = when {
        row.state == null -> R.string.applock_manage_action_indeterminate
        inStoredSet -> R.string.applock_manage_action_unprotect
        else -> R.string.applock_manage_action_protect
    }
    val actionDescriptionRes = when {
        row.state == null -> R.string.applock_manage_action_indeterminate_description
        inStoredSet -> R.string.applock_manage_action_unprotect_description
        else -> R.string.applock_manage_action_protect_description
    }
    // Named rather than inlined: an empty lambda inside a `when` branch leaves the expression's
    // type to inference, and an explicitly typed value cannot be misread.
    val noAction: () -> Unit = {}
    val onAction: () -> Unit = when {
        // Nothing may be claimed about this application's protection, so nothing is offered for it.
        row.state == null -> noAction
        inStoredSet -> onUnprotect
        else -> onProtect
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = NivaraSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        NivaraApplicationIcon(icon = icon)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NivaraSpacing.hairline)) {
            Text(text = row.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(id = stateRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val description = stringResource(id = actionDescriptionRes, row.label)
        OutlinedButton(
            onClick = onAction,
            enabled = canChange && row.state != null,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Text(text = stringResource(id = actionLabelRes))
        }
    }
}

/** Loads one row's icon, once per package name for as long as the row is composed. */
@Composable
private fun rememberApplicationIcon(
    loader: ApplicationIconLoader,
    packageName: String,
): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(null, loader, packageName) {
        value = loader.iconFor(packageName)
    }
    return icon
}

@Preview(name = "App Lock management – ready", showBackground = true)
@Composable
private fun AppLockManagementReadyPreview() {
    NivaraTheme {
        AppLockManagementScreen(
            uiState = previewState(),
            iconLoader = ApplicationIconLoader { null },
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onProtect = {},
            onUnprotect = {},
            onOpenPreparation = {},
            onUnlock = {},
        )
    }
}

@Preview(name = "App Lock management – locked", showBackground = true)
@Composable
private fun AppLockManagementLockedPreview() {
    NivaraTheme {
        AppLockManagementScreen(
            uiState = previewState().copy(
                sessionAuthenticated = false,
                missingPrerequisites = listOf(AppLockPrerequisite.Overlay),
            ),
            iconLoader = ApplicationIconLoader { null },
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onProtect = {},
            onUnprotect = {},
            onOpenPreparation = {},
            onUnlock = {},
        )
    }
}

@Preview(name = "App Lock management – no results", showBackground = true)
@Composable
private fun AppLockManagementNoResultsPreview() {
    NivaraTheme {
        AppLockManagementScreen(
            uiState = previewState().copy(
                rows = emptyList(),
                query = "zzz",
                emptiness = AppLockListEmptiness.NoSearchResults,
            ),
            iconLoader = ApplicationIconLoader { null },
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onProtect = {},
            onUnprotect = {},
            onOpenPreparation = {},
            onUnlock = {},
        )
    }
}

/** A ready state with a handful of rows, for previews only. */
private fun previewState(): AppLockManagementUiState.Ready = AppLockManagementUiState.Ready(
    rows = listOf(
        InstalledApplication("com.example.camera", "Camera") to ApplicationProtectionState.Protected,
        InstalledApplication("com.example.notes", "Notes") to ApplicationProtectionState.NotProtected,
        InstalledApplication("com.example.vault", "Vault") to ApplicationProtectionState.ProtectedButUnavailable,
    ).map { (application, state) -> ManagedApplication(application = application, state = state) },
    section = ApplicationSection.All,
    sort = ApplicationSortOrder.NameAscending,
    query = "",
    missingPrerequisites = emptyList(),
    runState = ProtectionRunState.Running,
    storedSetUnreadable = false,
    sessionAuthenticated = true,
    discoveredCount = 3,
    protectedCount = 2,
    protectedNotInstalledCount = 0,
)
