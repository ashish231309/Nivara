package com.nivara.app.ui.applock.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.domain.applock.AppLockOverlayState
import com.nivara.app.domain.applock.AppLockPhase
import com.nivara.app.domain.applock.OverlayUnavailability
import com.nivara.app.domain.applock.ProtectionAttemptOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.ui.biometric.toMessage
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.credential.CredentialEntry
import com.nivara.app.ui.credential.credentialTypeNameRes
import com.nivara.app.ui.credential.toFailureMessage

/**
 * The App Lock protection surface.
 *
 * ### What it shows, and what it refuses to show
 *
 * It shows Nivara's own explanation and the credential entry the rest of the application uses. It
 * does **not** show which application is protected: the package name is the identity the code keeps
 * and nothing a person needs to read, and a display name is a value another application chooses and
 * could change. A label would also invite exactly the mistake this stage must avoid — treating what
 * is *shown* as what is *protected*. The user knows which application they opened; the surface's job
 * is to gate it.
 *
 * Nothing here is a security decision. The entry widget hands over what was typed, the presenter
 * verifies it through the credential layer and hands the answer to the session gate, and this file
 * only decides what to draw.
 *
 * ### The way out
 *
 * There is one explicit action, [onLeave], which takes the user to the home screen, and it is the
 * same outcome as pressing Back. There is no "close" that reveals the protected application, because
 * closing the surface while the requirement still holds would be a one-tap bypass. Leaving is always
 * possible, is never blocked, and unlocks nothing.
 *
 * Visual refinement is deliberately out of scope: this is functional, readable, and built from the
 * application's existing components.
 */
@Composable
internal fun AppLockOverlaySurface(
    state: AppLockOverlayState,
    onCredential: (Long, CredentialInput) -> Unit,
    onBiometric: (Long) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state) {
            // No requirement: nothing is drawn. A window in this state is removed by the controller
            // in the same turn, so this is only the frame between the decision and its consequence.
            AppLockOverlayState.Idle -> Unit

            is AppLockOverlayState.Unpresentable -> UnpresentableContent(
                reason = state.reason,
                onLeave = onLeave,
            )

            is AppLockOverlayState.Required -> RequiredContent(
                state = state,
                onCredential = onCredential,
                onBiometric = onBiometric,
                onLeave = onLeave,
            )
        }
    }
}

@Composable
private fun RequiredContent(
    state: AppLockOverlayState.Required,
    onCredential: (Long, CredentialInput) -> Unit,
    onBiometric: (Long) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requestId = state.request.id
    val busy = state.phase == AppLockPhase.Authenticating
    val credential = state.credential

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.applock_overlay_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(id = R.string.applock_overlay_summary),
            style = MaterialTheme.typography.bodyLarge,
        )

        if (credential == null) {
            Text(
                text = stringResource(id = R.string.applock_overlay_no_credential_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(id = R.string.applock_overlay_no_credential_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(
                    id = R.string.applock_overlay_explanation,
                    stringResource(id = credentialTypeNameRes(credential)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CredentialEntry(
                type = credential,
                enabled = !busy,
                submitLabel = stringResource(id = R.string.credential_action_verify),
                onSubmit = { input -> onCredential(requestId, input) },
            )

            if (busy) {
                Text(
                    text = stringResource(id = R.string.applock_overlay_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.lastAttempt?.toMessage()?.let { message -> NivaraMessageText(message = message) }

            // The secondary path is offered only when it is actually usable: configured, and not
            // broken. Every other state leaves the primary credential in place, which is never
            // weakened and never hidden.
            if (state.biometric == BiometricStatus.Enabled) {
                Button(
                    onClick = { onBiometric(requestId) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(id = R.string.applock_overlay_biometric_action))
                }
            }
        }

        TextButton(
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.applock_overlay_leave_action))
        }
        Text(
            text = stringResource(id = R.string.applock_overlay_leave_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What is drawn when a requirement exists but cannot be presented.
 *
 * Reaching this on screen means a window was attached and then lost its capability; the honest
 * sentence is that protection could not be shown, which is the opposite of the one thing this stage
 * must never say — that nothing needed protecting.
 */
@Composable
private fun UnpresentableContent(
    reason: OverlayUnavailability,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.applock_overlay_unpresentable_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(id = reason.explanationRes()),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.applock_overlay_leave_action))
        }
    }
}

/** The message for the last attempt, or `null` when there is nothing to explain. */
private fun ProtectionAttemptOutcome.toMessage(): NivaraMessage? = when (this) {
    is ProtectionAttemptOutcome.Credential -> outcome.toFailureMessage()
    is ProtectionAttemptOutcome.Biometric -> outcome.toMessage()
}

/** Why the surface cannot be shown, in the user's words. */
private fun OverlayUnavailability.explanationRes(): Int = when (this) {
    OverlayUnavailability.NotGranted -> R.string.applock_overlay_unpresentable_not_granted
    OverlayUnavailability.Unavailable -> R.string.applock_overlay_unpresentable_unavailable
    OverlayUnavailability.Failed -> R.string.applock_overlay_unpresentable_failed
}
