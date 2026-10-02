package com.nivara.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.domain.permissions.BatteryOptimizationStatus
import com.nivara.app.domain.permissions.NotificationCapability
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.ui.theme.nivaraHeaderGradientColors

/**
 * The first-run gate: one bright screen that asks for the three capabilities Nivara needs —
 * Usage Access, overlay and the battery exemption — plus the optional notification permission.
 *
 * Every row is a fact, not a claim: the status line is the platform's current answer, the
 * button only opens Android's own screen or dialog, and the gate disappears by itself the
 * first time every required row reads granted. Nothing here grants anything.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onOpenUsage: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (gradientStart, gradientEnd) = nivaraHeaderGradientColors()
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onRefresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(listOf(gradientStart, gradientEnd))),
    ) {
        Column(modifier = Modifier.padding(NivaraSpacing.section)) {
            Text(
                text = stringResource(id = R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Spacer(modifier = Modifier.height(NivaraSpacing.small))
            Text(
                text = stringResource(id = R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(NivaraSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
            ) {
                PermissionRow(
                    iconRes = R.drawable.ic_usage_access,
                    title = stringResource(id = R.string.onboarding_usage_title),
                    summary = stringResource(id = R.string.onboarding_usage_summary),
                    status = statusText(state.usageAccess),
                    granted = state.usageAccess == UsageAccessStatus.Granted,
                    actionLabel = stringResource(id = R.string.onboarding_usage_action),
                    onAction = onOpenUsage,
                )
                PermissionRow(
                    iconRes = R.drawable.ic_overlay,
                    title = stringResource(id = R.string.onboarding_overlay_title),
                    summary = stringResource(id = R.string.onboarding_overlay_summary),
                    status = statusText(state.overlay),
                    granted = state.overlay == OverlayCapability.Granted,
                    actionLabel = stringResource(id = R.string.onboarding_overlay_action),
                    onAction = onOpenOverlay,
                )
                PermissionRow(
                    iconRes = R.drawable.ic_battery,
                    title = stringResource(id = R.string.onboarding_battery_title),
                    summary = stringResource(id = R.string.onboarding_battery_summary),
                    status = statusText(state.battery),
                    granted = state.battery == BatteryOptimizationStatus.Granted,
                    actionLabel = stringResource(id = R.string.onboarding_battery_action),
                    onAction = onRequestBattery,
                )
                if (state.notifications != NotificationCapability.NotRequestable) {
                    PermissionRow(
                        iconRes = R.drawable.ic_notification,
                        title = stringResource(id = R.string.onboarding_notifications_title),
                        summary = stringResource(id = R.string.onboarding_notifications_summary),
                        status = statusText(state.notifications),
                        granted = state.notifications == NotificationCapability.Granted,
                        actionLabel = stringResource(id = R.string.onboarding_notifications_action),
                        onAction = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        optional = true,
                    )
                }

                state.failure?.let { NivaraMessageText(message = it) }

                if (state.requiredGranted) {
                    Text(
                        text = stringResource(id = R.string.onboarding_ready),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = NivaraSpacing.row),
                    )
                }
            }
        }
    }
}

/**
 * One capability row: what it is, what Android currently says, and the one button that opens
 * the platform surface where it is granted. A granted row trades its button for a quiet check.
 */
@Composable
private fun PermissionRow(
    iconRes: Int,
    title: String,
    summary: String,
    status: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    optional: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(NivaraSpacing.screen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NivaraSpacing.hairline),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = status + if (optional) {
                        " · " + stringResource(id = R.string.onboarding_optional)
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (granted) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun statusText(status: UsageAccessStatus): String = when (status) {
    UsageAccessStatus.Granted -> stringResource(id = R.string.onboarding_status_granted)
    UsageAccessStatus.NotGranted -> stringResource(id = R.string.onboarding_status_not_granted)
    UsageAccessStatus.Unavailable -> stringResource(id = R.string.onboarding_status_unavailable)
}

@Composable
private fun statusText(capability: OverlayCapability): String = when (capability) {
    OverlayCapability.Granted -> stringResource(id = R.string.onboarding_status_granted)
    OverlayCapability.NotGranted -> stringResource(id = R.string.onboarding_status_not_granted)
    OverlayCapability.Unavailable -> stringResource(id = R.string.onboarding_status_unavailable)
}

@Composable
private fun statusText(status: BatteryOptimizationStatus): String = when (status) {
    BatteryOptimizationStatus.Granted -> stringResource(id = R.string.onboarding_status_granted)
    BatteryOptimizationStatus.NotGranted -> stringResource(id = R.string.onboarding_status_not_granted)
    BatteryOptimizationStatus.Unavailable -> stringResource(id = R.string.onboarding_status_unavailable)
}

@Composable
private fun statusText(capability: NotificationCapability): String = when (capability) {
    NotificationCapability.Granted -> stringResource(id = R.string.onboarding_status_granted)
    NotificationCapability.NotGranted -> stringResource(id = R.string.onboarding_status_not_granted)
    NotificationCapability.NotRequestable -> stringResource(id = R.string.onboarding_status_granted)
    NotificationCapability.Unavailable -> stringResource(id = R.string.onboarding_status_unavailable)
}
