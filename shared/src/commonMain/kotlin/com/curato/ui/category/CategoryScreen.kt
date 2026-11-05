package com.curato.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.ProductSummary
import com.curato.presentation.category.CategoryUiState
import com.curato.ui.designsystem.CuratoTopBar
import com.curato.ui.designsystem.PanelAction
import com.curato.ui.designsystem.ProductCardAspectRatio
import com.curato.ui.designsystem.ProductCard
import com.curato.ui.designsystem.ProductGridColumns
import com.curato.ui.designsystem.SectionHeader
import com.curato.ui.designsystem.fullWidthItem
import com.curato.ui.designsystem.ShimmerBox
import com.curato.ui.designsystem.ShimmerSection
import com.curato.ui.designsystem.Spacing
import com.curato.ui.designsystem.StatePanel

/**
 * Category (SPEC.md §7.3).
 *
 * One call fills the screen, and its response is split in two: the top rated in this
 * category, then the remainder. Both are a two-up grid rather than Home's horizontal
 * shelves — a shelf is a sampler you flick through, and this screen is where someone
 * browses the whole category, so vertical scrolling is the right gesture.
 *
 * Stateless, like every screen here: state in, callbacks out. It never touches Koin.
 */
@Composable
fun CategoryScreen(
    state: CategoryUiState,
    onProductClick: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()

    Surface(
        modifier = modifier
            .fillMaxSize()
            // Cutouts and gesture areas on the sides, which matter in landscape. The top
            // inset belongs to the app bar and the bottom one to the grid's padding, so
            // the content still scrolls under the system bars.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Null in every state but Content — the bar never shows a name derived from
            // the slug, because "Mens Clothing" correcting itself to "Men's Clothing" is
            // a visible flicker in place of an honest wait.
            CuratoTopBar(title = state.title, onBack = onBack)

            when (state) {
                is CategoryUiState.Loading ->
                    CategoryPlaceholder(bottomInset = safeInsets.calculateBottomPadding())

                is CategoryUiState.Failed ->
                    CategoryError(error = state.error, onRetry = onRetry, onBack = onBack)

                is CategoryUiState.Content -> CategoryGrid(
                    detail = state.detail,
                    showAdditional = state.showAdditional,
                    onProductClick = onProductClick,
                    bottomInset = safeInsets.calculateBottomPadding(),
                )
            }
        }
    }
}

/**
 * Both sections in a single grid.
 *
 * One grid, not a `LazyColumn` of two — a vertical grid cannot nest inside a vertical
 * list, and giving each section its own fixed-height grid would break lazy loading and
 * the scroll. The headers are full-width rows inside the same grid instead.
 */
@Composable
private fun CategoryGrid(
    detail: CategoryDetail,
    showAdditional: Boolean,
    onProductClick: (Int) -> Unit,
    bottomInset: Dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(ProductGridColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.gutter,
            end = Spacing.gutter,
            top = Spacing.x4,
            bottom = bottomInset + Spacing.x8,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
    ) {
        fullWidthItem { SectionHeader(title = "Top rated in ${detail.category.name}") }
        items(detail.topRated, key = { it.id }) { product ->
            CategoryCard(product, onProductClick)
        }

        // Omitted entirely when `additional` is empty (AC-6) — no header, no gap. A
        // category of five or fewer has no remainder, and a second heading above nothing
        // would invite a scroll that finds nothing.
        if (showAdditional) {
            fullWidthItem {
                SectionHeader(
                    title = "More in ${detail.category.name}",
                    // The grid already spaces rows by 12dp; this makes the gap between
                    // sections the 24dp the kit asks for.
                    modifier = Modifier.padding(top = Spacing.x3),
                )
            }
            items(detail.additional, key = { it.id }) { product ->
                CategoryCard(product, onProductClick)
            }
        }
    }
}

@Composable
private fun CategoryCard(product: ProductSummary, onProductClick: (Int) -> Unit) {
    ProductCard(
        title = product.title,
        price = product.price,
        currency = product.currency,
        imageUrl = product.imageUrl,
        rating = product.rating.rate,
        ratingCount = product.rating.count,
        onClick = { onProductClick(product.id) },
        // No width: the grid cell is the width, which is the whole reason the card
        // stopped setting its own.
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The loading state: no title in the bar, and a grid of placeholders.
 *
 * Four cards rather than a guess at the real count. The shelf on Home shows three
 * because it is a sampler; this screen always holds at least a full top-rated section,
 * so two rows of two is the honest shape of what is arriving.
 */
@Composable
private fun CategoryPlaceholder(bottomInset: Dp) {
    // One `ShimmerSection` around the whole grid, so every placeholder shares a single
    // clock and they sweep together instead of shimmering out of phase.
    ShimmerSection {
        LazyVerticalGrid(
            columns = GridCells.Fixed(ProductGridColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = Spacing.x4,
                bottom = bottomInset + Spacing.x8,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
            verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
            userScrollEnabled = false,
        ) {
            fullWidthItem {
                // Wrapped, because a grid measures its items at a *fixed* width rather
                // than a maximum one: `width(140.dp)` alone cannot shrink below the slot,
                // and the title placeholder comes out full width, reading as a banner.
                Box(modifier = Modifier.fillMaxWidth()) {
                    ShimmerBox(modifier = Modifier.width(140.dp).height(24.dp))
                }
            }
            items(PlaceholderCardCount) {
                // Squared off to the card's own proportions, so the real grid does not
                // reflow when it replaces this.
                ShimmerBox(modifier = Modifier.fillMaxWidth().aspectRatio(ProductCardAspectRatio))
            }
        }
    }
}

/**
 * A failure, full screen.
 *
 * The action list comes from [DomainError.isRetryable] rather than from a list of codes
 * kept here — `NOT_FOUND` is terminal, so it gets Back alone: the slug will not start
 * resolving, and a Retry that cannot succeed is worse than no Retry at all.
 */
@Composable
private fun CategoryError(error: DomainError, onRetry: () -> Unit, onBack: () -> Unit) {
    val notFound = error.code == ErrorCode.NOT_FOUND
    val back = PanelAction("Back to home", onBack, primary = notFound)

    StatePanel(
        glyph = if (notFound) "🗂" else "⚠",
        title = if (notFound) {
            "We could not find that category"
        } else {
            "Could not load this category"
        },
        body = if (notFound) {
            "It may have been renamed or removed from the catalogue."
        } else {
            error.userMessage
        },
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
