package com.curato.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * A section title, with an optional trailing action.
 *
 * The action is *View all* on a category shelf. The whole header is hidden with its
 * section and never shown empty — a heading above nothing is a promise the screen does
 * not keep.
 *
 * **The caller supplies the gutter**, as `Modifier.padding(horizontal = Spacing.gutter)`.
 * A header sitting directly in a list needs it; one inside a grid whose `contentPadding`
 * already provides it does not, and a component that insisted on its own would indent
 * that header to 32dp while the cards beside it sat at 16dp.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            // Announced as a heading so a screen reader can jump between sections
            // rather than reading the whole shelf to find the next one.
            modifier = Modifier.semantics { heading() },
        )
        if (actionLabel != null && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
