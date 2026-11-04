package com.curato.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign

/** An action offered by a [StatePanel]. Absent actions are the point, not an oversight. */
data class PanelAction(
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = true,
)

/**
 * Empty, error and not-found — one component for all three.
 *
 * Actions are passed in rather than inferred, which is what lets a non-retryable failure
 * simply get no Retry. SPEC.md §7.6 is explicit that offering Retry where retrying
 * cannot help is worse than offering nothing: a product that no longer exists will not
 * start existing because the customer tapped again.
 */
@Composable
fun StatePanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    actions: List<PanelAction> = emptyList(),
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.displaySmall,
                // Decorative: the title says the same thing in words, so announcing the
                // glyph would just read punctuation aloud.
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = Spacing.x2),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                actions.forEach { action ->
                    if (action.primary) {
                        Button(
                            onClick = action.onClick,
                            modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.full),
                        ) { Text(action.label) }
                    } else {
                        TextButton(
                            onClick = action.onClick,
                            modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget),
                        ) { Text(action.label) }
                    }
                }
            }
        }
    }
}
