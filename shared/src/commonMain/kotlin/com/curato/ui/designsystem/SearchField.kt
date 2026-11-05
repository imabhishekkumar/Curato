package com.curato.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The glyph, per the kit's `.a-searchbar svg { width: 20px }`. */
private val MagnifierSize: Dp = 20.dp

/**
 * The search field (SPEC.md §7.4).
 *
 * The kit's `.a-searchbar.active`: a pill on `surface` with a hairline outline, a leading
 * magnifier, and a caret in the primary colour. It lives inside [CuratoTopBar] rather
 * than above the content, which is what the kit draws and why the bar carries no title.
 *
 * **Fully rounded**, which the kit reserves for the two things tapped to go somewhere —
 * this and buttons. It deliberately matches [SearchBarButton] on Home at the same radius
 * and the same `bodyLarge` text size: the pill tapped there becomes the pill typed in
 * here, so the transition reads as the same object gaining a cursor.
 *
 * **No label and no clear button.** The kit's component table lists a labelled
 * `AppTextField` against this screen, but the Search frames it actually draws are a
 * label-less pill, and the sprite has no clear glyph to put in one. The drawn artifact is
 * the more specific of the two, so it wins; the labelled variant belongs to Enquiry, where
 * it is the thing the kit draws.
 *
 * [onQueryChange] fires per keystroke. Whether that becomes a request is the caller's
 * business — this component never debounces and never decides a term is too short.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search products",
    label: String = "Search products",
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // 48dp rather than the mockup's 44px, for the reason the back button is 48dp:
            // the kit's own accessibility constants put the floor there and say it is
            // enforced in these components.
            .defaultMinSize(minHeight = MinTouchTarget),
        shape = RoundedCornerShape(Radius.full),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.full))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(Radius.full),
                )
                .padding(horizontal = Spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Magnifier(
                // Decorative: the field itself is labelled, so announcing the glyph would
                // read "search" twice.
                modifier = Modifier.size(MagnifierSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    // The kit's 10px gap, rounded onto the 4dp scale its Metrics sheet
                    // requires — "every gap in the app is one of these, never an ad-hoc
                    // number".
                    .padding(start = Spacing.x3)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = label },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                // A search term is a product name, not prose: autocorrect would quietly
                // rewrite "Fjallraven" into something the catalogue does not contain.
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                // There is nothing to submit — results arrive as the term is typed — so
                // the action key reveals them instead of being inert.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            ) { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    field()
                }
            }
        }
    }

    FocusOnEmpty(query = query, focusRequester = focusRequester)
}

/**
 * The kit's `#g-search`, drawn rather than imported.
 *
 * `Icons.Filled.Search` would mean adding `material-icons-extended` — some two thousand
 * vectors to ship one glyph — so the kit's own definition is reproduced: a circle at
 * (11, 11) with radius 6 and a handle from (16, 16) to (20, 20), on a 24-unit square with
 * a 1.8 round-capped stroke.
 */
@Composable
private fun Magnifier(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(width = 1.8f * unit, cap = StrokeCap.Round)
        drawCircle(
            color = tint,
            radius = 6f * unit,
            center = Offset(11f * unit, 11f * unit),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(16f * unit, 16f * unit),
            end = Offset(20f * unit, 20f * unit),
            strokeWidth = 1.8f * unit,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Focus on arrival, but only when there is nothing in the field.
 *
 * The kit says the field "keeps focus", and an empty one is the case where the customer
 * has no choice but to type — so the keyboard should already be up. A field arriving with
 * a term in it already has results behind it, and opening a keyboard over the thing
 * someone came back to look at would hide it.
 *
 * Keyed on [Unit] rather than on [query]: this is a decision made once, on arrival, not a
 * rule that re-grabs focus every time the field is cleared.
 */
@Composable
private fun FocusOnEmpty(query: String, focusRequester: FocusRequester) {
    val startedEmpty = remember { query.isEmpty() }
    LaunchedEffect(Unit) {
        if (startedEmpty) focusRequester.requestFocus()
    }
}
