package com.nivara.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.domain.app.HomeSettingsOpener
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.ui.credential.credentialTypeNameRes
import com.nivara.app.ui.home.HomeUiState
import com.nivara.app.ui.home.HomeViewModel

/**
 * The "everything else" screen: every feature that is not one of the home's four tiles, in one
 * plain list with short labels and no paragraphs. This is the drawer the simplified home keeps
 * the advanced features in — biometrics, disguise, recovery, the launcher choice, preparation
 * and about — each one row, one tap, one existing screen.
 */
@Composable
fun AllFeaturesRoute(
    onOpenCredentialSetup: () -> Unit,
    onOpenCredentialVerify: () -> Unit,
    onOpenBiometric: () -> Unit,
    onOpenPreparation: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val homeState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val homeSettingsOpener = (context.applicationContext as NivaraApplication).container.homeSettingsOpener

    AllFeaturesScreen(
        credentialConfigured = (homeState as? HomeUiState.Ready)?.credentialType != null,
        credentialTypeRes = (homeState as? HomeUiState.Ready)?.credentialType
            ?.let { credentialTypeNameRes(it) },
        onOpenCredential = {
            if ((homeState as? HomeUiState.Ready)?.credentialType != null) {
                onOpenCredentialVerify()
            } else {
                onOpenCredentialSetup()
            }
        },
        onOpenBiometric = onOpenBiometric,
        onOpenPreparation = onOpenPreparation,
        onOpenLauncher = { homeSettingsOpener.openHomeSettings() },
        onOpenCamouflage = onOpenCamouflage,
        onOpenRecovery = onOpenRecovery,
        onOpenAbout = onOpenAbout,
        modifier = modifier,
    )
}

@Composable
private fun AllFeaturesScreen(
    credentialConfigured: Boolean,
    credentialTypeRes: Int?,
    onOpenCredential: () -> Unit,
    onOpenBiometric: () -> Unit,
    onOpenPreparation: () -> Unit,
    onOpenLauncher: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = NivaraSpacing.row),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight),
    ) {
        FeatureHeader(text = stringResource(id = R.string.all_features_security_header))
        FeatureRow(
            iconRes = R.drawable.ic_key,
            title = stringResource(id = R.string.all_features_credential_title),
            summary = if (credentialConfigured && credentialTypeRes != null) {
                stringResource(id = R.string.all_features_credential_summary_some, stringResource(id = credentialTypeRes))
            } else {
                stringResource(id = R.string.all_features_credential_summary_none)
            },
            onClick = onOpenCredential,
        )
        FeatureRow(
            iconRes = R.drawable.ic_shield_check,
            title = stringResource(id = R.string.all_features_biometric_title),
            summary = stringResource(id = R.string.all_features_biometric_summary),
            onClick = onOpenBiometric,
        )
        FeatureHeader(text = stringResource(id = R.string.all_features_protection_header))
        FeatureRow(
            iconRes = R.drawable.ic_lock,
            title = stringResource(id = R.string.all_features_prep_title),
            summary = stringResource(id = R.string.all_features_prep_summary),
            onClick = onOpenPreparation,
        )
        FeatureRow(
            iconRes = R.drawable.ic_home,
            title = stringResource(id = R.string.all_features_launcher_title),
            summary = stringResource(id = R.string.all_features_launcher_summary),
            onClick = onOpenLauncher,
        )
        FeatureHeader(text = stringResource(id = R.string.all_features_privacy_header))
        FeatureRow(
            iconRes = R.drawable.ic_eye_off,
            title = stringResource(id = R.string.all_features_camouflage_title),
            summary = stringResource(id = R.string.all_features_camouflage_summary),
            onClick = onOpenCamouflage,
        )
        FeatureRow(
            iconRes = R.drawable.ic_restore,
            title = stringResource(id = R.string.all_features_recovery_title),
            summary = stringResource(id = R.string.all_features_recovery_summary),
            onClick = onOpenRecovery,
        )
        FeatureHeader(text = stringResource(id = R.string.all_features_info_header))
        FeatureRow(
            iconRes = R.drawable.ic_info,
            title = stringResource(id = R.string.all_features_about_title),
            summary = stringResource(id = R.string.all_features_about_summary),
            onClick = onOpenAbout,
        )
    }
}

@Composable
private fun FeatureHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = NivaraSpacing.screen, top = NivaraSpacing.row, bottom = NivaraSpacing.tight),
    )
}

@Composable
private fun FeatureRow(
    iconRes: Int,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.Center),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
