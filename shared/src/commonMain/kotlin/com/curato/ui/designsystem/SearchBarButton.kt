package com.curato.ui.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * The search affordance on Home.
 *
 * **Not an input.** It navigates to Search, where the real field lives — so it is a
 * button with a text label, announced as a button, and typing never happens here.
 * Fully rounded, which the kit reserves for the two things tapped to go somewhere.
 */
@Composable
fun SearchBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search products",
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            // Stated explicitly: it looks like a text field, so without this a screen
            // reader would invite the customer to type somewhere nothing can be typed.
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(Radius.full),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = placeholder, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
