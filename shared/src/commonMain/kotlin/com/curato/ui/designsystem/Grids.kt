package com.curato.ui.designsystem

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable

/**
 * The product grid, shared by Category and Search.
 *
 * Both screens draw the kit's `.a-grid` — two equal columns, 12dp apart, inside the 16dp
 * gutter — so the numbers and the span helper live here rather than twice. The kit's own
 * rule is the reason: anything a feature needs that is not already in the package is a
 * candidate for the package, not a private constant in a screen file.
 */

/**
 * Two up.
 *
 * Not a horizontal shelf: a shelf is a sampler you flick through, and both of these
 * screens are places someone reads a whole list, so vertical scrolling is the gesture.
 */
const val ProductGridColumns = 2

/**
 * A card's height relative to its width: a square image, two title lines, a price and a
 * rating line, plus the gutter top and bottom.
 *
 * Used by the loading placeholders so the real grid does not reflow when it replaces them.
 */
const val ProductCardAspectRatio = 0.62f

/**
 * An item spanning every column, so a grid can hold a section title or a result count.
 *
 * `maxLineSpan` rather than a literal [ProductGridColumns], so this stays correct if the
 * column count ever becomes responsive.
 */
fun LazyGridScope.fullWidthItem(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}
