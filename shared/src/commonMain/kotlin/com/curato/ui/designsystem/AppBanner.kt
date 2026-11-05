package com.curato.ui.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curato.presentation.enquiry.BannerKind

/** The kit's `.a-banner svg { width: 17px }`. */
private val BannerGlyphSize: Dp = 18.dp

/**
 * The kit's `.a-banner` — a screen-level failure stated above the content it concerns.
 *
 * Used where an error annotates a form rather than replacing it, which is why it is a
 * banner and not a [StatePanel]: the customer's values are still on screen and still
 * valid to send, so the screen must not be taken away from them to say so.
 *
 * It renders `userMessage` and nothing else. SPEC.md §5 makes that the only general error
 * string shown to a customer — `developerMessage` is dropped at the API boundary and
 * never reaches this layer, and `traceId` has its own component ([TraceIdRow]) because it
 * is a diagnostic rather than a message.
 */
@Composable
fun AppBanner(
    message: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.WARNING,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.field),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x3, vertical = Spacing.x3)
                // The whole row announces, so the glyph's meaning and the message arrive
                // together rather than as two events.
                .semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            val tint = MaterialTheme.colorScheme.onErrorContainer
            // The kit varies the glyph and nothing else: "wait a moment" and "something
            // is wrong" want different reactions, and the wording alone does not carry
            // that at a glance.
            when (kind) {
                BannerKind.VALIDATION, BannerKind.WARNING -> WarningGlyph(
                    tint = tint,
                    modifier = Modifier.size(BannerGlyphSize),
                )

                BannerKind.DELAY -> ClockGlyph(
                    tint = tint,
                    modifier = Modifier.size(BannerGlyphSize),
                )
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                modifier = Modifier.padding(start = Spacing.x3),
            )
        }
    }
}
