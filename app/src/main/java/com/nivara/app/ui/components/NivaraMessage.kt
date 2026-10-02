package com.nivara.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * A message shown to the user: a string resource plus, for the few messages that need one, the
 * number to format into it.
 *
 * Deliberately not a `String`. Mapping typed failures to text happens in the feature that owns
 * them, so the domain layers stay free of user-facing wording, and no failure object ever reaches
 * a composable.
 */
data class NivaraMessage(
    @StringRes val textRes: Int,
    val argument: Long? = null,
)

/** Seconds to display for a delay, rounded up and never below one. */
fun secondsFromMillis(millis: Long): Long =
    if (millis <= MILLIS_PER_SECOND) 1L else (millis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND

private const val MILLIS_PER_SECOND = 1_000L

/** Renders a [NivaraMessage], formatting [NivaraMessage.argument] when it has one. */
@Composable
fun NivaraMessageText(
    message: NivaraMessage,
    modifier: Modifier = Modifier,
) {
    val text = message.argument?.let { argument -> stringResource(id = message.textRes, argument) }
        ?: stringResource(id = message.textRes)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}
