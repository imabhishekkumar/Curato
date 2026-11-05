package com.curato.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The kit's `.a-btn .spin`: 17px with a 2.2px stroke. */
private val SpinnerSize: Dp = 18.dp
private val SpinnerStroke: Dp = 2.dp

/**
 * The screen's one committing action.
 *
 * **Non-re-entrant while loading.** The click is gated inside the component rather than
 * at each call site, because the thing this prevents is a double tap sending two
 * enquiries — a guard that has to hold everywhere it is used, not everywhere someone
 * remembered. A disabled `Button` already swallows the second tap, but the gate is
 * explicit so the rule survives someone later passing `enabled = true` while a request
 * is in flight.
 *
 * Full width and pill-shaped: the kit reserves [Radius.full] for buttons and the search
 * bar, "the only two things that are tapped to go somewhere".
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val active = enabled && !loading

    Button(
        onClick = { if (active) onClick() },
        enabled = active,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            // Announced rather than implied: a spinner is invisible to a screen reader,
            // so without this the button simply goes quiet mid-send.
            .semantics { if (loading) stateDescription = "Sending" },
        shape = RoundedCornerShape(Radius.full),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            // The kit's `.a-btn.off`: a disabled button stays a surface rather than
            // fading out, so it reads as "not yet" instead of as a rendering glitch.
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(SpinnerSize),
                strokeWidth = SpinnerStroke,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
