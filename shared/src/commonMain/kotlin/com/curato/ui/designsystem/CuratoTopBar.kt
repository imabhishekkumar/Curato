package com.curato.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 52dp, per the kit's `.a-appbar`. */
private val TopBarHeight: Dp = 52.dp

/** The glyph, not its touch target — which is [MinTouchTarget]. */
private val BackGlyphSize: Dp = 22.dp

/**
 * The app bar: a back control, and a title the screen has actually verified.
 *
 * **[title] is nullable, and that is the point.** SPEC.md §7.3 forbids deriving a
 * category's name from its slug, and the kit says why: a placeholder built from
 * `mens-clothing` would read "Mens Clothing" and then correct itself to "Men's
 * Clothing" — a visible flicker in place of an honest wait. So the title is a property
 * of loaded content, and every state that has not loaded any passes null. An error state
 * passes null too: there is no verified name to show.
 *
 * [elevated] is the kit's `.elev` treatment — a `surfaceContainer` ground and a hairline
 * `outlineVariant` rule — and it is a per-screen choice rather than a scroll state. The
 * kit draws it on the screens whose content starts flush against the bar, and drops it
 * on the product screen, where the hero panel sits directly beneath and a rule would
 * compete with the panel's own edge.
 */
@Composable
fun CuratoTopBar(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
    backContentDescription: String = "Back",
) {
    CuratoTopBar(
        onBack = onBack,
        modifier = modifier,
        elevated = elevated,
        backContentDescription = backContentDescription,
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                // One line with an ellipsis, per the kit. A category name that wrapped
                // would change the bar's height between screens.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.x2),
            )
        }
    }
}

/**
 * The same bar, with whatever the screen needs where a title would go.
 *
 * Search puts an editable field there, which is the kit's own arrangement — the field
 * lives in the app bar rather than above the content, so the one row that keeps Back
 * reachable is not spent twice. The inset handling, the 52dp height, the 48dp back target
 * and the `elevated` rule are all here rather than copied into that screen.
 *
 * [content] is given a `RowScope`, so a field can claim the remaining width with
 * `Modifier.weight(1f)` exactly as the kit's `flex: 1` does.
 */
@Composable
fun CuratoTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
    backContentDescription: String = "Back",
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (elevated) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        // The inset is padding *inside* the Surface, so its colour runs up under the
        // status bar. Applied outside, the bar would float below a transparent strip.
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .fillMaxWidth()
                .height(TopBarHeight)
                .padding(start = Spacing.x1, end = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                // 48dp even though the kit's mockup CSS draws a 44px button: the kit's own
                // accessibility constants put the floor at 48dp and say it is enforced in
                // the design-system components, which is here.
                modifier = Modifier
                    .size(MinTouchTarget)
                    .semantics { contentDescription = backContentDescription },
            ) {
                BackChevron(
                    modifier = Modifier.size(BackGlyphSize),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            content()
        }
    }

    if (elevated) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * The kit's `#g-back`, drawn rather than imported.
 *
 * `Icons.AutoMirrored.Filled.ArrowBack` would mean adding `material-icons-extended` —
 * some two thousand vectors to ship one chevron. The kit defines this glyph as a single
 * stroked path, `M15 5l-7 7 7 7` on a 24-unit square with a 1.9 round-capped stroke, so
 * it is reproduced here at whatever size the caller asks for.
 */
@Composable
private fun BackChevron(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = size.minDimension / 24f
        val chevron = Path().apply {
            moveTo(15f * unit, 5f * unit)
            lineTo(8f * unit, 12f * unit)
            lineTo(15f * unit, 19f * unit)
        }
        drawPath(
            path = chevron,
            color = tint,
            style = Stroke(
                width = 1.9f * unit,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
