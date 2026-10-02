package com.nivara.app.ui.about

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nivara.app.BuildConfig
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the about screen. Build information is read here so that the screen
 * itself stays free of platform access and can be previewed.
 */
@Composable
fun AboutRoute(modifier: Modifier = Modifier) {
    AboutScreen(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        buildType = BuildConfig.BUILD_TYPE,
        applicationId = BuildConfig.APPLICATION_ID,
        platformApiLevel = Build.VERSION.SDK_INT,
        modifier = modifier,
    )
}

/**
 * Stateless about screen: shows which build is installed and which privacy defaults apply.
 */
@Composable
fun AboutScreen(
    appVersion: String,
    buildType: String,
    applicationId: String,
    platformApiLevel: Int,
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
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(id = R.string.home_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(NivaraSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
            ) {
                InfoRow(label = stringResource(id = R.string.about_version_label), value = appVersion)
                InfoRow(label = stringResource(id = R.string.about_build_type_label), value = buildType)
                InfoRow(label = stringResource(id = R.string.about_package_label), value = applicationId)
                InfoRow(
                    label = stringResource(id = R.string.about_platform_label),
                    value = platformApiLevel.toString(),
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    text = stringResource(id = R.string.about_privacy_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(id = R.string.about_privacy_summary),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "About", showBackground = true)
@Composable
private fun AboutScreenPreview() {
    NivaraTheme {
        AboutScreen(
            appVersion = "0.1.0 (1)",
            buildType = "debug",
            applicationId = "com.nivara.app",
            platformApiLevel = 34,
        )
    }
}
