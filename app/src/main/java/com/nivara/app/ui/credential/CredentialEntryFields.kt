package com.nivara.app.ui.credential

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.PatternCanonicalizer
import com.nivara.app.domain.credential.PrimaryCredentialType

/**
 * Entry widgets, one per credential type.
 *
 * Each widget collects what the user typed or drew, hands it over as a [CredentialInput] and
 * drops its own copy immediately. Nothing here validates, derives or compares anything: that is
 * the credential layer's job, and keeping it there is what makes the rules testable without a
 * device.
 *
 * Security-relevant behaviour of these widgets:
 *
 * - The PIN is entered on an in-app keypad, so no keyboard (which could learn or sync what is
 *   typed) is ever involved, and the digits are shown as dots.
 * - The password uses a single-line obfuscated field with autofill and suggestions left off,
 *   because there is nowhere legitimate for them to go. Its text is a `String`, which cannot be
 *   cleared — a platform limitation that is documented rather than hidden — so the field is
 *   emptied the moment the value is handed over.
 * - The pattern is an actual drawing surface, not a text field, and the points are handed over as
 *   the raw gesture for canonicalisation.
 * - None of the widgets keeps a value after submission, none writes to the clipboard, and none
 *   uses `rememberSaveable`, so nothing here survives process death.
 */
@Composable
fun CredentialEntry(
    type: PrimaryCredentialType,
    enabled: Boolean,
    submitLabel: String,
    onSubmit: (CredentialInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (type) {
        PrimaryCredentialType.Pin ->
            PinEntry(enabled = enabled, submitLabel = submitLabel, onSubmit = onSubmit, modifier = modifier)
        PrimaryCredentialType.Password ->
            PasswordEntry(enabled = enabled, submitLabel = submitLabel, onSubmit = onSubmit, modifier = modifier)
        // A pattern is committed by lifting the finger, so it has no submit button of its own.
        PrimaryCredentialType.Pattern ->
            PatternEntry(enabled = enabled, onSubmit = onSubmit, modifier = modifier)
    }
}

@Composable
private fun PinEntry(
    enabled: Boolean,
    submitLabel: String,
    onSubmit: (CredentialInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The typed digits live in a plain character buffer, never in a String, and the buffer is
    // overwritten as soon as it has been handed over.
    val buffer = remember { PinBuffer(MAXIMUM_PIN_DIGITS) }
    var entered by remember { mutableStateOf(0) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row)) {
        PinDots(filled = entered, modifier = Modifier.align(Alignment.CenterHorizontally))

        PinKeypad(
            enabled = enabled,
            onDigit = { digit ->
                buffer.append(digit)
                entered = buffer.length
            },
            onBackspace = {
                buffer.removeLast()
                entered = buffer.length
            },
        )

        Button(
            onClick = {
                val input = buffer.toInput()
                buffer.clear()
                entered = 0
                onSubmit(input)
            },
            enabled = enabled && entered > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = submitLabel)
        }
    }
}

@Composable
private fun PinDots(filled: Int, modifier: Modifier = Modifier) {
    val filledColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.outline
    val slots = filled.coerceAtLeast(MINIMUM_PIN_SLOTS)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative on purpose: the number of dots is shown, the digits never are, and no
        // accessibility description repeats any part of the credential.
        repeat(slots) { index ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (index < filled) filledColor else emptyColor),
            )
        }
    }
}

@Composable
private fun PinKeypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        KEYPAD_ROWS.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
                row.forEach { label ->
                    if (label.isEmpty()) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        TextButton(
                            onClick = { onDigit(label[0]) },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Text(text = label, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = onBackspace,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.credential_pin_backspace))
        }
    }
}

@Composable
private fun PasswordEntry(
    enabled: Boolean,
    submitLabel: String,
    onSubmit: (CredentialInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row)) {
        OutlinedTextField(
            value = text,
            onValueChange = { updated -> text = updated },
            label = { Text(text = stringResource(id = R.string.credential_password_label)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val characters = text.toCharArray()
                text = ""
                onSubmit(CredentialInput.Password(characters))
            },
            enabled = enabled && text.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = submitLabel)
        }
    }
}

@Composable
private fun PatternEntry(
    enabled: Boolean,
    onSubmit: (CredentialInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(emptyList<Int>()) }
    var pointer by remember { mutableStateOf<Offset?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.outline
    val activeDotColor = MaterialTheme.colorScheme.primary
    val description = stringResource(id = R.string.credential_pattern_area_description)

    val drawn = selected
    val currentPointer = pointer

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { position ->
                        val point = hitTest(position, size.width, size.height)
                        selected = if (point == null) emptyList() else listOf(point)
                        pointer = position
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val point = hitTest(change.position, size.width, size.height)
                        // A point that is already part of the drawing is not a new connection:
                        // dragging back over the same dot does not change the pattern.
                        if (point != null && !selected.contains(point)) {
                            selected = selected + point
                        }
                        pointer = change.position
                    },
                    onDragEnd = {
                        pointer = null
                        val points = selected.toIntArray()
                        selected = emptyList()
                        if (points.isNotEmpty()) {
                            onSubmit(CredentialInput.Pattern(points))
                        }
                    },
                    onDragCancel = {
                        pointer = null
                        selected = emptyList()
                    },
                )
            },
    ) {
        val cell = size.minDimension / PatternCanonicalizer.GRID_SIZE
        val dotRadius = cell * DOT_RADIUS_FRACTION
        val strokeWidth = cell * LINE_WIDTH_FRACTION

        for (index in 0 until drawn.size - 1) {
            drawLine(
                color = lineColor,
                start = centerOf(index = drawn[index], cell = cell),
                end = centerOf(index = drawn[index + 1], cell = cell),
                strokeWidth = strokeWidth,
            )
        }
        if (currentPointer != null && drawn.isNotEmpty()) {
            drawLine(
                color = lineColor,
                start = centerOf(index = drawn.last(), cell = cell),
                end = currentPointer,
                strokeWidth = strokeWidth,
            )
        }
        for (index in 0 until PatternCanonicalizer.POINT_COUNT) {
            drawCircle(
                color = if (drawn.contains(index)) activeDotColor else dotColor,
                radius = dotRadius,
                center = centerOf(index = index, cell = cell),
            )
        }
    }
}

/** Center of grid point [index] for a cell of [cell] pixels. */
private fun centerOf(index: Int, cell: Float): Offset {
    val column = index % PatternCanonicalizer.GRID_SIZE
    val row = index / PatternCanonicalizer.GRID_SIZE
    return Offset((column + 0.5f) * cell, (row + 0.5f) * cell)
}

/** The grid point under [position], or `null` when the touch is not close enough to one. */
private fun hitTest(position: Offset, widthPx: Int, heightPx: Int): Int? {
    val cell = minOf(widthPx, heightPx).toFloat() / PatternCanonicalizer.GRID_SIZE
    if (cell <= 0f) return null
    val radius = cell * HIT_RADIUS_FRACTION
    for (index in 0 until PatternCanonicalizer.POINT_COUNT) {
        if ((position - centerOf(index = index, cell = cell)).getDistance() <= radius) return index
    }
    return null
}

/**
 * Digits typed for a PIN.
 *
 * A plain character buffer rather than a list of boxed characters or a `String`: it can be
 * overwritten, and appending a digit allocates nothing.
 */
private class PinBuffer(private val maximumLength: Int) {

    private val digits = CharArray(maximumLength)

    var length: Int = 0
        private set

    fun append(digit: Char) {
        if (length >= digits.size) return
        digits[length] = digit
        length++
    }

    fun removeLast() {
        if (length == 0) return
        length--
        digits[length] = ZERO_CHAR
    }

    fun toInput(): CredentialInput.Pin = CredentialInput.Pin(digits.copyOf(length))

    fun clear() {
        digits.fill(ZERO_CHAR)
        length = 0
    }

    private companion object {
        const val ZERO_CHAR: Char = '\u0000'
    }
}

/** The 4x3 keypad, with the bottom corners left empty. */
private val KEYPAD_ROWS: List<List<String>> = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("", "0", ""),
)

/** Matches the policy's maximum PIN length. Entry stops there rather than growing without bound. */
private const val MAXIMUM_PIN_DIGITS = 12

/** Shown before anything is typed, so the field has a visible shape to begin with. */
private const val MINIMUM_PIN_SLOTS = 4

private const val DOT_RADIUS_FRACTION = 0.09f
private const val HIT_RADIUS_FRACTION = 0.22f
private const val LINE_WIDTH_FRACTION = 0.03f
