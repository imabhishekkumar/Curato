package com.curato.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch

/**
 * The kit's `.a-trace` — the only place a diagnostic string is ever shown.
 *
 * `traceId` correlates a customer-visible failure with the server log line that holds the
 * real cause, and SPEC.md §5 surfaces it on `INTERNAL_ERROR` alone, "in a copyable form".
 * Copyable is the whole requirement: a ten-character id read off a screen and retyped into
 * a support message is an id that arrives wrong.
 *
 * It does **not** decide when to appear. `DomainError` carries a `traceId` only for
 * `INTERNAL_ERROR` — dropped at the API boundary for every other code — so the gate is
 * upstream and this component cannot leak one by accident.
 *
 * Monospaced because the id is a token to be compared character by character, not prose.
 */
@Composable
fun TraceIdRow(
    traceId: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.small),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.x3, end = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "trace $traceId",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Read as a label rather than as ten letters: a screen reader spelling an
                // id aloud is no more useful than the screen showing it.
                modifier = Modifier.semantics {
                    contentDescription = "Diagnostic trace identifier"
                },
            )

            TextButton(
                onClick = {
                    scope.launch {
                        // `setClipEntry` suspends, which is why this is the modern
                        // `Clipboard` rather than the deprecated non-suspending manager.
                        clipboard.setClipEntry(plainTextClipEntry(traceId))
                        copied = true
                    }
                },
            ) {
                // The label changes and stays changed. A timed revert would be a second
                // clock on screen for a confirmation nobody needs to see twice.
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
