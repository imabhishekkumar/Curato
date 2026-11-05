package com.curato.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bar that holds a screen's committing action, pinned within thumb reach.
 *
 * Never inline at the end of a scroll: SPEC.md §7.5's AC-10 requires the action to be
 * reachable *at any content length*, and a button that lives after an eight-hundred
 * character description is one a customer has to go looking for.
 *
 * **It carries `imePadding()` and `navigationBarsPadding()` itself**, which is the whole
 * reason it is a component rather than a `Box` at the bottom of each screen. The product
 * screen has no keyboard and would never have caught the omission; the enquiry form
 * would have shipped with its Send button underneath the IME.
 *
 * Callers are responsible for clearing the scroll behind it — [Spacing.stickyBarClearance]
 * as bottom padding — so the last line of content is never hidden under the bar.
 */
@Composable
fun StickyActionBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // A hairline rather than a shadow: the bar sits on content that scrolls under
            // it, and a rule states that edge without implying a floating layer.
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Inside the Surface, so its colour fills the gesture-bar strip
                    // instead of leaving a transparent band beneath the button.
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(
                        start = Spacing.gutter,
                        end = Spacing.gutter,
                        top = Spacing.x3,
                        bottom = Spacing.x4,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                content()
            }
        }
    }
}
