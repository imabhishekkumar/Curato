package com.curato.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.FeaturedSource
import com.curato.domain.model.ProductSummary
import com.curato.domain.model.RecentProduct
import com.curato.presentation.home.HomeUiState
import com.curato.presentation.home.SectionState
import com.curato.ui.designsystem.PanelAction
import com.curato.ui.designsystem.ProductCard
import com.curato.ui.designsystem.SearchBarButton
import com.curato.ui.designsystem.SectionHeader
import com.curato.ui.designsystem.ShelfCardWidth
import com.curato.ui.designsystem.ShimmerBox
import com.curato.ui.designsystem.ShimmerSection
import com.curato.ui.designsystem.Spacing
import com.curato.ui.designsystem.StatePanel

/**
 * Home (SPEC.md §7.2).
 *
 * Section order is fixed: search → recently viewed → top rated → category shelves.
 * There is no chip row and no filter — every category is a shelf, so nothing is hidden
 * behind an interaction and, after first paint, browsing Home issues no further
 * requests.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSearchClick: () -> Unit,
    onProductClick: (Int) -> Unit,
    onViewAllClick: (String) -> Unit,
    onRetryFeatured: () -> Unit,
    onRetryCategories: () -> Unit,
    onRetryAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Edge to edge, but never edge to content. The background runs under the status and
    // navigation bars so the app does not look letterboxed; the content is inset from
    // them, because a price or a tappable card sitting under the system bars is either
    // unreadable or unreachable.
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()

    Surface(
        modifier = modifier
            .fillMaxSize()
            // Cutouts and gesture areas on the sides, which matter in landscape.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        color = MaterialTheme.colorScheme.background,
    ) {
        // When both calls fail the screen shows one error and one Retry, not two panels
        // saying the same thing.
        if (state.bothFailed) {
            val error = state.screenError
            StatePanel(
                glyph = "⚠",
                title = "Could not load the catalogue",
                body = error?.userMessage ?: "Please try again.",
                actions = listOf(PanelAction("Retry", onRetryAll)),
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                    .padding(top = Spacing.x12),
            )
            return@Surface
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Inset as content padding rather than as a margin, so the list still
            // scrolls *under* the bars while its first and last items clear them.
            contentPadding = PaddingValues(
                top = safeInsets.calculateTopPadding() + Spacing.x4,
                bottom = safeInsets.calculateBottomPadding() + Spacing.x8,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.betweenSections),
        ) {
            item {
                SearchBarButton(
                    onClick = onSearchClick,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }

            // Hidden entirely on first run — a heading above nothing is a promise the
            // screen does not keep.
            if (state.showRecentlyViewed) {
                item { RecentlyViewedShelf(state.recentlyViewed, onProductClick) }
            }

            item {
                FeaturedSection(
                    state = state.featured,
                    onProductClick = onProductClick,
                    onRetry = onRetryFeatured,
                )
            }

            categorySection(
                state = state.categories,
                onProductClick = onProductClick,
                onViewAllClick = onViewAllClick,
                onRetry = onRetryCategories,
            )
        }
    }
}

@Composable
private fun RecentlyViewedShelf(items: List<RecentProduct>, onProductClick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        SectionHeader(title = "Recently viewed")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
        ) {
            items(items, key = { it.id }) { product ->
                // No rating: the snapshot carries none, and the card omits the star line
                // rather than inventing one.
                ProductCard(
                    title = product.title,
                    price = product.price,
                    currency = product.currency,
                    imageUrl = product.imageUrl,
                    onClick = { onProductClick(product.id) },
                )
            }
        }
    }
}

@Composable
private fun FeaturedSection(
    state: SectionState<FeaturedShelf>,
    onProductClick: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is SectionState.Loading -> ShelfPlaceholder(title = null)

        is SectionState.Failed -> SectionError(
            message = state.error.userMessage,
            retryable = state.error.isRetryable,
            onRetry = onRetry,
        )

        is SectionState.Content -> {
            val shelf = state.value
            ProductShelf(
                // The heading branches on `source` alone and never inspects the
                // products. A curated list is not a ranking, so calling it "Top rated"
                // would be a lie on the first screen anyone sees.
                title = when (shelf.source) {
                    FeaturedSource.COMPUTED -> "Top rated"
                    FeaturedSource.CURATED -> "Featured"
                },
                products = shelf.products,
                onProductClick = onProductClick,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.categorySection(
    state: SectionState<List<CategoryShelf>>,
    onProductClick: (Int) -> Unit,
    onViewAllClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is SectionState.Loading -> {
            items(2) { ShelfPlaceholder(title = null) }
        }

        is SectionState.Failed -> item {
            SectionError(
                message = state.error.userMessage,
                retryable = state.error.isRetryable,
                onRetry = onRetry,
            )
        }

        is SectionState.Content -> items(
            items = state.value,
            key = { it.category.slug },
        ) { shelf ->
            ProductShelf(
                title = shelf.category.name,
                products = shelf.topRated,
                onProductClick = onProductClick,
                actionLabel = "View all",
                onActionClick = { onViewAllClick(shelf.category.slug) },
            )
        }
    }
}

@Composable
private fun ProductShelf(
    title: String,
    products: List<ProductSummary>,
    onProductClick: (Int) -> Unit,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        SectionHeader(title = title, actionLabel = actionLabel, onActionClick = onActionClick)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
        ) {
            items(products, key = { it.id }) { product ->
                ProductCard(
                    title = product.title,
                    price = product.price,
                    currency = product.currency,
                    imageUrl = product.imageUrl,
                    rating = product.rating.rate,
                    ratingCount = product.rating.count,
                    onClick = { onProductClick(product.id) },
                )
            }
        }
    }
}

/** A shelf's loading state. One clock for the whole section, so the cards move together. */
@Composable
private fun ShelfPlaceholder(title: String?) {
    ShimmerSection {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            ShimmerBox(
                modifier = Modifier
                    .padding(horizontal = Spacing.gutter)
                    .width(140.dp)
                    .height(24.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.betweenCards),
                userScrollEnabled = false,
            ) {
                items(3) {
                    ShimmerBox(modifier = Modifier.width(ShelfCardWidth).height(260.dp))
                }
            }
        }
    }
}

/** A section-level failure, scoped to its own section rather than the whole screen. */
@Composable
private fun SectionError(message: String, retryable: Boolean, onRetry: () -> Unit) {
    StatePanel(
        title = "This section could not load",
        body = message,
        // Retry only where retrying can help.
        actions = if (retryable) listOf(PanelAction("Retry", onRetry)) else emptyList(),
        modifier = Modifier.fillMaxWidth(),
    )
}
