package com.curato.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curato.domain.DomainError
import com.curato.domain.model.ProductSummary
import com.curato.domain.model.SearchResults
import com.curato.presentation.search.SearchUiState
import com.curato.ui.designsystem.CuratoTopBar
import com.curato.ui.designsystem.PanelAction
import com.curato.ui.designsystem.ProductCard
import com.curato.ui.designsystem.ProductCardAspectRatio
import com.curato.ui.designsystem.ProductGridColumns
import com.curato.ui.designsystem.SearchField
import com.curato.ui.designsystem.ShimmerBox
import com.curato.ui.designsystem.ShimmerSection
import com.curato.ui.designsystem.Spacing
import com.curato.ui.designsystem.StatePanel
import com.curato.ui.designsystem.fullWidthItem

/**
 * Search (SPEC.md §7.4).
 *
 * Its own destination rather than a mode Home drops into, which is what lets back from a
 * product return to these results with Home still intact behind them (AC-7).
 *
 * Stateless, like every screen here: state in, callbacks out. [query] arrives separately
 * from [state] because the two are independent — the field can hold a term the results on
 * screen have not caught up with, which is exactly what the kit draws.
 */
@Composable
fun SearchScreen(
    query: String,
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onProductClick: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onBrowseCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // No title: the field is what the bar carries, per the kit. Not elevated
            // either — the field already has its own outline, and a rule directly beneath
            // it would read as a second edge.
            CuratoTopBar(onBack = onBack, elevated = false) {
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    // The kit's `flex: 1` — the field takes whatever the back button left.
                    modifier = Modifier.weight(1f).padding(start = Spacing.x1),
                )
            }

            // **The content is inset by the keyboard, and it has to be.** This is the
            // first screen with a keyboard and no `StickyActionBar` — which is where the
            // kit otherwise puts `imePadding()`. Without it a `StatePanel` centres itself
            // in a viewport that extends under the IME, and "Browse categories" and Retry
            // end up drawn but untappable. A scrolling grid survives that; a centred panel
            // does not.
            Box(modifier = Modifier.weight(1f).imePadding()) {
                when (state) {
                    is SearchUiState.BelowMinimum -> KeepTyping(state.message)
                    is SearchUiState.Loading -> SearchPlaceholder()
                    is SearchUiState.Failed -> SearchError(state.error, onRetry, onBack)
                    is SearchUiState.Results -> if (state.showEmptyState) {
                        NoMatches(term = state.results.query, onBrowseCategories = onBrowseCategories)
                    } else {
                        ResultsGrid(
                            results = state.results,
                            onProductClick = onProductClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The results, two up, with the count as the first thing that scrolls.
 *
 * The count is an item in the grid rather than a row above it, which is how the kit draws
 * it: it belongs to the results, so it should leave the screen with them rather than
 * sitting fixed above a list it is no longer describing.
 */
@Composable
private fun ResultsGrid(
    results: SearchResults,
    onProductClick: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(ProductGridColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.gutter,
            end = Spacing.gutter,
            top = Spacing.x3,
            // The system-bar inset is the trailing spacer below, not this value: a
            // modifier re-reads it on every layout pass, where a composition-time read
            // can latch the zero that the first frame sees.
            bottom = Spacing.x8,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
    ) {
        fullWidthItem {
            Text(
                text = resultCount(results.products.size, results.query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.x1),
            )
        }

        items(results.products, key = { it.id }) { product ->
            SearchResultCard(product, onProductClick)
        }

        // `systemBars`, deliberately not `safeDrawing`: `safeDrawing` includes the IME,
        // and the keyboard is handled by `imePadding()` on the content itself. Counting
        // it in both places would leave a keyboard's worth of empty scroll under the
        // last row.
        fullWidthItem { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars)) }
    }
}

@Composable
private fun SearchResultCard(product: ProductSummary, onProductClick: (Int) -> Unit) {
    ProductCard(
        title = product.title,
        price = product.price,
        currency = product.currency,
        imageUrl = product.imageUrl,
        rating = product.rating.rate,
        ratingCount = product.rating.count,
        onClick = { onProductClick(product.id) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * `2 results for “bag”`.
 *
 * [term] is the query the **server** echoed, not the field's contents. SPEC.md §4.4
 * matches against `description`, which a card does not show, so a result can appear to
 * match nothing on it — naming the matched term is what turns that from a bug into a
 * search. Curly quotes, as the kit sets them.
 */
private fun resultCount(count: Int, term: String): String {
    val noun = if (count == 1) "result" else "results"
    return "$count $noun for “$term”"
}

/**
 * Zero matches — an empty state, not an error (AC-8).
 *
 * No Retry, because nothing failed. The one action goes back to Home, which is where the
 * category shelves the copy points at actually live, and it is non-primary: the customer
 * came here to search, and the field above is still the more likely next move.
 */
@Composable
private fun NoMatches(term: String, onBrowseCategories: () -> Unit) {
    StatePanel(
        glyph = "🔍",
        title = "No products match “$term”",
        body = "Try a shorter term, or browse a category from home.",
        actions = listOf(
            PanelAction("Browse categories", onBrowseCategories, primary = false),
        ),
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Below two characters: no request was made, and nothing failed.
 *
 * [message] rather than a literal, so a field error the backend returns anyway is shown
 * in the server's own words instead of the client's near-copy of them.
 */
@Composable
private fun KeepTyping(message: String) {
    StatePanel(
        glyph = "⌨",
        title = "Keep typing",
        body = message,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Loading: the shape of a grid, and a line where the count will be.
 *
 * The kit draws no loading frame for Search, so this is Category's — the two screens share
 * a grid, and a placeholder that did not match it would make the results jump when they
 * land. The count row is reserved for the same reason: it is 20dp of height that appears
 * above the first card, and leaving it out moves the whole grid up as the shimmer clears.
 * Reserving the space is not claiming a number — [CountLineHeight] is the line box, not a
 * guess at how many matches there are.
 *
 * Four cards, likewise, rather than a guess at the row count: how many things match is the
 * one fact a search cannot know in advance.
 */
@Composable
private fun SearchPlaceholder() {
    // One section, so every placeholder sweeps on a single clock instead of drifting out
    // of phase with the others.
    ShimmerSection {
        LazyVerticalGrid(
            columns = GridCells.Fixed(ProductGridColumns),
            modifier = Modifier.fillMaxSize(),
            // Identical to the results grid, deliberately — see above.
            contentPadding = PaddingValues(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = Spacing.x3,
                bottom = Spacing.x8,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
            verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
            userScrollEnabled = false,
        ) {
            fullWidthItem {
                // Boxed, because a grid measures its items at a *fixed* width rather than
                // a maximum: a bare `width(...)` cannot shrink below the slot and the line
                // comes out full width, reading as a banner.
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.x1)) {
                    ShimmerBox(
                        modifier = Modifier.width(CountLineWidth).height(CountLineHeight),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
            }
            items(PlaceholderCardCount) {
                ShimmerBox(modifier = Modifier.fillMaxWidth().aspectRatio(ProductCardAspectRatio))
            }
        }
    }
}

/**
 * A failure, full screen.
 *
 * Undrawn in the kit but reachable: SPEC.md §4.4 lists `UPSTREAM_UNAVAILABLE` and
 * `INTERNAL_ERROR`, and the client adds `NETWORK_UNAVAILABLE` and `TIMEOUT` on top. The
 * action list comes from [DomainError.isRetryable] rather than a second list of codes kept
 * here, the same as Category and Product. The field above stays editable throughout, so a
 * different term is always available without going back first.
 */
@Composable
private fun SearchError(error: DomainError, onRetry: () -> Unit, onBack: () -> Unit) {
    val back = PanelAction("Back to home", onBack, primary = !error.isRetryable)

    StatePanel(
        glyph = "⚠",
        title = "Could not run that search",
        body = error.userMessage,
        actions = if (error.isRetryable) {
            listOf(PanelAction("Retry", onRetry), back)
        } else {
            listOf(back)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Two rows of two — enough to read as a grid without claiming to know the real count. */
private const val PlaceholderCardCount = 4

/** `bodySmall`'s line box, so the reserved row is exactly the height the real one takes. */
private val CountLineHeight: Dp = 16.dp

/** About the width of `4 results for “cotton”` — a placeholder, not a measurement. */
private val CountLineWidth: Dp = 150.dp
