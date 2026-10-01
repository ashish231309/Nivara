package com.nivara.app.ui.apphide.management

import androidx.annotation.StringRes
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
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.ApplicationVisibility
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.applications.NivaraApplicationIcon
import com.nivara.app.ui.components.NivaraErrorState
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.credential.SecureScreenEffect
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the hidden-application management screen.
 *
 * It creates the view model, observes its state, re-reads everything when the screen is resumed, and
 * supplies the icon loader from the application container. The screen itself stays stateless.
 */
@Composable
fun HiddenManagementRoute(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HiddenManagementViewModel = viewModel(factory = HiddenManagementViewModel.Factory),
    iconLoader: ApplicationIconLoader = rememberApplicationIconLoader(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The screen lists the device's applications and which of them the user hides. Android treats the
    // installed-application list as personal data, and this screen is more sensitive still — it is
    // where an application is taken out of sight — so the same window flag the credential and App
    // Lock screens use keeps it out of screenshots, recordings and the recents thumbnail. There is
    // one implementation of it, and this is not another one.
    SecureScreenEffect()

    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    HiddenManagementScreen(
        uiState = uiState,
        iconLoader = iconLoader,
        onRetry = viewModel::refresh,
        onQueryChange = viewModel::onQueryChange,
        onSortChange = viewModel::onSortChange,
        onSectionChange = viewModel::onSectionChange,
        onHide = viewModel::hide,
        onUnhide = viewModel::unhide,
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
 * Stateless hidden-application management screen: renders [HiddenManagementUiState] and reports user
 * actions upwards.
 *
 * ### What it shows
 *
 * The applications the device can launch, whether each of them is hidden, and a summary line saying
 * what hiding does and does not do. The screen is explicit that this is a Nivara preference and not
 * a device-wide change: a user who hides an application here must not be left believing it has
 * disappeared from Android.
 *
 * ### What it does not do
 *
 * It never decides anything: the rows are what the view model read, and a tap reports the intent. It
 * does not name a package anywhere — the package name is an identity the code keeps, not something a
 * person needs to read — it shows nothing about App Lock, and it changes nothing on the device.
 */
@Composable
fun HiddenManagementScreen(
    uiState: HiddenManagementUiState,
    iconLoader: ApplicationIconLoader,
    onRetry: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (ApplicationSortOrder) -> Unit,
    onSectionChange: (HiddenSection) -> Unit,
    onHide: (InstalledApplication) -> Unit,
    onUnhide: (InstalledApplication) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        HiddenManagementUiState.Loading -> NivaraLoadingState(modifier = modifier)
        HiddenManagementUiState.Error -> NivaraErrorState(onRetry = onRetry, modifier = modifier)
        is HiddenManagementUiState.Ready -> HiddenManagementContent(
            state = uiState,
            iconLoader = iconLoader,
            onQueryChange = onQueryChange,
            onSortChange = onSortChange,
            onSectionChange = onSectionChange,
            onHide = onHide,
            onUnhide = onUnhide,
            onUnlock = onUnlock,
            modifier = modifier,
        )
    }
}

@Composable
private fun HiddenManagementContent(
    state: HiddenManagementUiState.Ready,
    iconLoader: ApplicationIconLoader,
    onQueryChange: (String) -> Unit,
    onSortChange: (ApplicationSortOrder) -> Unit,
    onSectionChange: (HiddenSection) -> Unit,
    onHide: (InstalledApplication) -> Unit,
    onUnhide: (InstalledApplication) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(state = state)

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
            label = { Text(text = stringResource(id = R.string.apphide_manage_search_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        SectionSelector(section = state.section, onSectionChange = onSectionChange)
        SortSelector(sort = state.sort, onSortChange = onSortChange)

        HiddenApplicationList(
            state = state,
            iconLoader = iconLoader,
            canChange = state.canChange,
            onHide = onHide,
            onUnhide = onUnhide,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What hiding means here, and how much of the device's applications is hidden.
 *
 * The first line is the one that matters most: hiding is Nivara's own preference, and Android's
 * launcher still shows every application in this list. The count is stated only when the stored set
 * could be read; when it could not, the card says so instead of showing a number nobody has.
 */
@Composable
private fun SummaryCard(
    state: HiddenManagementUiState.Ready,
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(id = R.string.apphide_manage_summary),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.apphide_manage_launcher_note),
                style = MaterialTheme.typography.bodySmall,
            )
            when (val hiddenState = state.hiddenState) {
                is HiddenStateAvailability.Available -> {
                    Text(
                        text = stringResource(
                            id = R.string.apphide_manage_counts,
                            hiddenState.hiddenCount,
                            state.discoveredCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hiddenState.notInstalledCount > 0) {
                        Text(
                            text = stringResource(
                                id = R.string.apphide_manage_not_installed,
                                hiddenState.notInstalledCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HiddenStateAvailability.Unreadable -> Text(
                    text = stringResource(id = R.string.apphide_manage_state_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                )

                HiddenStateAvailability.Unavailable -> Text(
                    text = stringResource(id = R.string.apphide_manage_state_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Shown while the session gate is closed.
 *
 * A change is refused in that state, so the screen says so before the user taps anything, and offers
 * the one way to open the gate: the existing credential screen. Nothing here authenticates anybody,
 * and nothing here keeps a session of its own.
 */
@Composable
private fun LockedCard(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(id = R.string.apphide_manage_locked),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.apphide_manage_unlock_action))
        }
    }
}

/**
 * All applications, or only the hidden ones.
 *
 * A radio group rather than a chip row: the two options are exclusive, and a radio group gives
 * accessibility services the state of each option without any extra work. The hidden option stays
 * selectable while the stored set cannot be read: the section then shows its own sentence explaining
 * that the set could not be read, which informs the user, whereas a control that silently did
 * nothing would leave them guessing.
 */
@Composable
private fun SectionSelector(
    section: HiddenSection,
    onSectionChange: (HiddenSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(id = R.string.apphide_manage_section_label),
            style = MaterialTheme.typography.labelLarge,
        )
        SelectableOption(
            labelRes = R.string.apphide_manage_section_all,
            selected = section == HiddenSection.All,
            onSelect = { onSectionChange(HiddenSection.All) },
        )
        SelectableOption(
            labelRes = R.string.apphide_manage_section_hidden,
            selected = section == HiddenSection.Hidden,
            onSelect = { onSectionChange(HiddenSection.Hidden) },
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(id = R.string.apphide_manage_sort_label),
            style = MaterialTheme.typography.labelLarge,
        )
        SelectableOption(
            labelRes = R.string.apphide_manage_sort_name_ascending,
            selected = sort == ApplicationSortOrder.NameAscending,
            onSelect = { onSortChange(ApplicationSortOrder.NameAscending) },
        )
        SelectableOption(
            labelRes = R.string.apphide_manage_sort_name_descending,
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
private fun HiddenApplicationList(
    state: HiddenManagementUiState.Ready,
    iconLoader: ApplicationIconLoader,
    canChange: Boolean,
    onHide: (InstalledApplication) -> Unit,
    onUnhide: (InstalledApplication) -> Unit,
    modifier: Modifier = Modifier,
) {
    val emptyMessageRes = when (state.emptiness) {
        HiddenListEmptiness.DeviceHasNoApplications -> R.string.apphide_manage_empty_device
        HiddenListEmptiness.NoSearchResults -> R.string.apphide_manage_empty_search
        HiddenListEmptiness.NothingHidden -> R.string.apphide_manage_empty_hidden
        HiddenListEmptiness.HiddenStateUnreadable -> R.string.apphide_manage_empty_unreadable
        HiddenListEmptiness.HiddenStateUnavailable -> R.string.apphide_manage_empty_unavailable
        null -> null
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items = state.rows, key = { row -> row.packageName }) { row ->
            HiddenApplicationRow(
                row = row,
                iconLoader = iconLoader,
                canChange = canChange,
                onHide = { onHide(row.application) },
                onUnhide = { onUnhide(row.application) },
            )
        }
        if (state.rows.isEmpty() && emptyMessageRes != null) {
            item(key = "empty") {
                Text(
                    text = stringResource(id = emptyMessageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * One application: its icon, its label, whether it is hidden, and the control that changes that.
 *
 * The state is written out in words as well as shown on the button, so nothing depends on colour
 * alone. When the stored set could not be read the row says so and the control is disabled: a
 * control that cannot be trusted is worse than none, and a row that looked visible would be a claim
 * Nivara cannot make — one that would also be wrong exactly when it matters most.
 */
@Composable
private fun HiddenApplicationRow(
    row: ManagedHiddenApplication,
    iconLoader: ApplicationIconLoader,
    canChange: Boolean,
    onHide: () -> Unit,
    onUnhide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = rememberApplicationIcon(iconLoader, row.packageName)
    val stateRes = when (row.visibility) {
        ApplicationVisibility.Hidden -> R.string.apphide_manage_state_hidden
        ApplicationVisibility.Visible -> R.string.apphide_manage_state_visible
        null -> R.string.apphide_manage_state_unknown
    }
    val isHidden = row.visibility == ApplicationVisibility.Hidden
    val actionLabelRes = when {
        row.visibility == null -> R.string.apphide_manage_action_indeterminate
        isHidden -> R.string.apphide_manage_action_unhide
        else -> R.string.apphide_manage_action_hide
    }
    val actionDescriptionRes = when {
        row.visibility == null -> R.string.apphide_manage_action_indeterminate_description
        isHidden -> R.string.apphide_manage_action_unhide_description
        else -> R.string.apphide_manage_action_hide_description
    }

    // Named rather than inlined: an empty lambda inside a `when` branch leaves the expression's type
    // to inference, and an explicitly typed value cannot be misread.
    val noAction: () -> Unit = {}
    val onAction: () -> Unit = when {
        // Nothing may be claimed about this application's visibility, so nothing is offered for it.
        row.visibility == null -> noAction
        isHidden -> onUnhide
        else -> onHide
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NivaraApplicationIcon(icon = icon)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            enabled = canChange && row.visibility != null,
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

@Preview(name = "Hidden applications – ready", showBackground = true)
@Composable
private fun HiddenManagementReadyPreview() {
    NivaraTheme {
        HiddenManagementScreen(
            uiState = previewState(),
            iconLoader = ApplicationIconLoader { null },
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onHide = {},
            onUnhide = {},
            onUnlock = {},
        )
    }
}

@Preview(name = "Hidden applications – locked", showBackground = true)
@Composable
private fun HiddenManagementLockedPreview() {
    NivaraTheme {
        HiddenManagementScreen(
            uiState = previewState().copy(sessionAuthenticated = false),
            iconLoader = ApplicationIconLoader { null },
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onHide = {},
            onUnhide = {},
            onUnlock = {},
        )
    }
}

@Preview(name = "Hidden applications – nothing hidden", showBackground = true)
@Composable
private fun HiddenManagementNothingHiddenPreview() {
    NivaraTheme {
        HiddenManagementScreen(
            uiState = previewState().copy(
                rows = emptyList(),
                section = HiddenSection.Hidden,
                emptiness = HiddenListEmptiness.NothingHidden,
            ),
            iconLoader = ApplicationIconLoader { null },
            onRetry = {},
            onQueryChange = {},
            onSortChange = {},
            onSectionChange = {},
            onHide = {},
            onUnhide = {},
            onUnlock = {},
        )
    }
}

/** A ready state with a handful of rows, for previews only. */
private fun previewState(): HiddenManagementUiState.Ready = HiddenManagementUiState.Ready(
    rows = listOf(
        InstalledApplication("com.example.camera", "Camera") to ApplicationVisibility.Hidden,
        InstalledApplication("com.example.notes", "Notes") to ApplicationVisibility.Visible,
    ).map { (application, visibility) ->
        ManagedHiddenApplication(application = application, visibility = visibility)
    },
    section = HiddenSection.All,
    sort = ApplicationSortOrder.NameAscending,
    query = "",
    hiddenState = HiddenStateAvailability.Available(hiddenCount = 1, notInstalledCount = 0),
    sessionAuthenticated = true,
    discoveredCount = 2,
)
