package com.nivara.app.ui.camouflage

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraSize
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.ui.components.dismissibleMessage
import com.nivara.app.domain.camouflage.CamouflageProfile
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the application-identity screen.
 *
 * It creates the view model, observes its state, re-reads the identity when the screen is resumed —
 * which is how a change made outside Nivara is noticed — and routes a locked change into the
 * existing credential screen. The screen itself stays stateless.
 *
 * There is no screenshot protection here and none is needed: the screen shows the names Nivara can
 * present under, which is configuration rather than personal data, and it shows no application the
 * user has hidden or protected.
 */
@Composable
fun CamouflageRoute(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CamouflageViewModel = viewModel(factory = CamouflageViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    // The view model reports that a change needs a session; this screen does not authenticate and
    // changes nothing by itself. It asks for the existing credential screen.
    val ready = uiState as? CamouflageUiState.Ready
    LaunchedEffect(ready?.unlockRequired) {
        if (ready?.unlockRequired == true) {
            viewModel.onUnlockHandled()
            onUnlock()
        }
    }

    CamouflageScreen(
        uiState = uiState,
        onSelect = viewModel::select,
        onUnlock = onUnlock,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * Stateless application-identity screen: renders [CamouflageUiState] and reports user actions
 * upwards.
 *
 * ### What it shows
 *
 * The identities Nivara can present under, which one the device is showing now, and two pieces of
 * copy that matter more than the controls: what camouflage does not do, and how to get back to
 * Nivara whatever it is currently called. The limitation is stated at the same size as the feature
 * rather than in a footnote, because a user who reads only this screen must not come away believing
 * that renaming the icon hides anything from Android or from anyone holding the phone.
 *
 * ### What it does not do
 *
 * It decides nothing: the list is the declared identities, the selected one is what the view model
 * read from the device, and a tap reports the choice. It does not authenticate, does not name a
 * package anywhere, and does not mention App Lock or hidden applications — an identity has nothing
 * to do with either.
 */
@Composable
fun CamouflageScreen(
    uiState: CamouflageUiState,
    onSelect: (CamouflageProfile) -> Unit,
    onUnlock: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        CamouflageUiState.Loading -> NivaraLoadingState(modifier = modifier)

        is CamouflageUiState.Ready -> CamouflageContent(
            state = uiState,
            onSelect = onSelect,
            onUnlock = onUnlock,
            onMessageShown = onMessageShown,
            modifier = modifier,
        )
    }
}

@Composable
private fun CamouflageContent(
    state: CamouflageUiState.Ready,
    onSelect: (CamouflageProfile) -> Unit,
    onUnlock: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Text(
            text = stringResource(id = R.string.camouflage_summary),
            style = MaterialTheme.typography.bodyMedium,
        )

        IdentityCard(
            titleRes = R.string.camouflage_current_title,
            bodyRes = R.string.camouflage_current_summary,
            bodyArgument = stringResource(id = state.selected.labelRes()),
            profile = state.selected,
        )

        Text(
            text = stringResource(id = R.string.camouflage_choose_label),
            style = MaterialTheme.typography.titleMedium,
        )

        CamouflageProfile.entries.forEach { profile ->
            IdentityRow(
                profile = profile,
                selected = profile == state.selected,
                enabled = state.canChange && profile != state.selected,
                onSelect = { onSelect(profile) },
            )
        }

        if (!state.sessionAuthenticated) {
            Text(
                text = stringResource(id = R.string.camouflage_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.camouflage_unlock_action))
            }
        }

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

        IdentityCard(
            titleRes = R.string.camouflage_limitation_title,
            bodyRes = R.string.camouflage_limitation,
            profile = null,
        )

        IdentityCard(
            titleRes = R.string.camouflage_recovery_title,
            bodyRes = R.string.camouflage_recovery,
            profile = null,
        )
    }
}

/**
 * One card of the screen: a title, a body, and — when it is about an identity — that identity's
 * badge.
 *
 * @param bodyArgument the name of the selected identity, for the copy that names it, or `null` when
 *   the body takes no argument.
 */
@Composable
private fun IdentityCard(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    profile: CamouflageProfile?,
    bodyArgument: String? = null,
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
            modifier = Modifier.padding(NivaraSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
            ) {
                profile?.let { identity -> IdentityBadge(identity) }
                Text(
                    text = stringResource(id = titleRes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = bodyArgument?.let { argument -> stringResource(id = bodyRes, argument) }
                    ?: stringResource(id = bodyRes),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * One identity the user can choose.
 *
 * The whole row is the target and it carries the identity's name, so a screen reader announces what
 * can be chosen; the badge beside it is decoration and carries no description of its own. The
 * action is a plain button rather than a gesture or a long press, for the same reason the rest of
 * Nivara's configuration is.
 */
@Composable
private fun IdentityRow(
    profile: CamouflageProfile,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = NivaraSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        IdentityBadge(profile)
        Text(
            text = stringResource(id = profile.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onSelect, enabled = enabled) {
            Text(
                text = stringResource(
                    id = if (selected) {
                        R.string.camouflage_action_current
                    } else {
                        R.string.camouflage_action_use
                    },
                ),
            )
        }
    }
}

/** The identity's glyph on its background colour: the same composition the launcher draws. */
@Composable
private fun IdentityBadge(
    profile: CamouflageProfile,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(NivaraSize.rowIcon)
            .background(color = colorResource(id = profile.backgroundRes()), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = profile.foregroundRes()),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Makes a message tappable so it can be dismissed, as the launcher's surface does. */
@Preview(name = "Identity – authenticated", showBackground = true)
@Composable
private fun CamouflageReadyPreview() {
    NivaraTheme {
        CamouflageScreen(
            uiState = previewState(),
            onSelect = {},
            onUnlock = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "Identity – locked", showBackground = true)
@Composable
private fun CamouflageLockedPreview() {
    NivaraTheme {
        CamouflageScreen(
            uiState = previewState().copy(sessionAuthenticated = false),
            onSelect = {},
            onUnlock = {},
            onMessageShown = {},
        )
    }
}

/** A ready state for previews only. */
private fun previewState(): CamouflageUiState.Ready = CamouflageUiState.Ready(
    selected = CamouflageProfile.Nivara,
    sessionAuthenticated = true,
)
