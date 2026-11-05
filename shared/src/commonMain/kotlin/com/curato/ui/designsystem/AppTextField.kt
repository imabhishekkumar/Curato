package com.curato.ui.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The kit's `.a-field .box { min-height: 50px }`, floored at the touch target. */
private val FieldMinHeight: Dp = 50.dp

/** The kit's `.a-field .box.multi { min-height: 76px }`. */
private val MultilineMinHeight: Dp = 76.dp

/** The kit's `padding: 13px 14px`, rounded onto the 4dp scale. */
private val FieldPaddingHorizontal: Dp = 16.dp
private val FieldPaddingVertical: Dp = Spacing.x3

/**
 * The kit's `.a-field` — the labelled text field (SPEC.md §7.6).
 *
 * `BasicTextField` with a `decorationBox` rather than Material 3's `TextField`, which
 * brings a container, its own label animation and a 56dp minimum this would have to
 * fight. Same call [SearchField] made, for the same reason: the kit specifies the box, so
 * the box is drawn rather than approximated.
 *
 * **The label is always rendered**, which the kit's component table states outright. It is
 * not a placeholder that floats: DECISIONS' accessibility note is that placeholder-only
 * fields "vanish on focus and are read unreliably", and this is the screen where losing
 * track of which input you are in actually costs something.
 *
 * [errorMessage] **replaces** [supportingText] rather than stacking under it — also the
 * kit's rule, and two lines of small print under one input is noise at the moment the
 * customer most needs one clear instruction.
 *
 * [onFocusLost] exists for SPEC.md §6's "on blur" validation. Validation deliberately does
 * not happen per keystroke, so the component reports the blur and the caller decides.
 */
@Composable
fun AppTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorMessage: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onFocusLost: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val isError = errorMessage != null
    val colors = MaterialTheme.colorScheme

    // The kit's three border treatments, in the order they override each other: an error
    // outranks focus, because a 2dp primary ring on a field that is wrong would read as
    // approval.
    val borderWidth = if (isError || focused) 2.dp else 1.dp
    val borderColour = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.outline
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        // The kit's 5px label-to-box gap, on the 4dp scale.
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            // The kit tints the label itself on error, not just the border — a border is
            // easy to miss on a screen holding four of them.
            color = if (isError) colors.error else colors.primary,
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(
                    minHeight = if (singleLine) FieldMinHeight else MultilineMinHeight,
                )
                .onFocusChanged { focusState ->
                    // Only the *transition* out of focus is a blur. Recomposition can
                    // deliver the same unfocused state repeatedly, and re-validating on
                    // each would make a settled error flicker.
                    if (focused && !focusState.isFocused) onFocusLost()
                    focused = focusState.isFocused
                }
                .semantics { if (errorMessage != null) error(errorMessage) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            ),
            keyboardOptions = keyboardOptions,
            singleLine = singleLine,
            cursorBrush = SolidColor(colors.primary),
        ) { field ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(
                        minHeight = if (singleLine) FieldMinHeight else MultilineMinHeight,
                    )
                    .border(
                        width = borderWidth,
                        color = borderColour,
                        shape = RoundedCornerShape(Radius.field),
                    )
                    .padding(
                        horizontal = FieldPaddingHorizontal,
                        vertical = FieldPaddingVertical,
                    ),
                // Top, not centre: a multiline box grows downward, and a message starting
                // in the vertical middle of an empty box looks like a layout fault.
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                field()
            }
        }

        // One line, never two: the error is the supporting text while there is an error.
        val helper = errorMessage ?: supportingText
        if (helper != null) {
            Text(
                text = helper,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (isError) colors.error else colors.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = Spacing.x1)
                    // Announced rather than merely coloured. Without this a screen reader
                    // goes silent on a 422 — the field turns red and says nothing.
                    .semantics {
                        if (isError) liveRegion = LiveRegionMode.Polite
                    },
            )
        }
    }
}
