package com.curato.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.model.Product
import com.curato.presentation.product.ProductUiState
import com.curato.ui.designsystem.CuratoTopBar
import com.curato.ui.designsystem.PanelAction
import com.curato.ui.designsystem.PrimaryButton
import com.curato.ui.designsystem.ShimmerBox
import com.curato.ui.designsystem.ShimmerSection
import com.curato.ui.designsystem.Spacing
import com.curato.ui.designsystem.StatePanel
import com.curato.ui.designsystem.StickyActionBar
import com.curato.ui.designsystem.TABULAR_FIGURES
import com.curato.ui.designsystem.formatPrice

/**
 * The kit's `.a-hero`: `aspect-ratio: 1.15`, inset by the gutter rather than full-bleed.
 *
 * Upstream images top out at 646px on the long edge (SPEC.md §10.4), so a full-width
 * hero would upscale and go soft. Inset on a panel, the drawn size stays close to native.
 */
private const val HeroAspectRatio = 1.15f

/** The eyebrow's tracking, per the kit's `letter-spacing: 0.07em` at 11sp. */
private val EyebrowTracking = 0.8.sp

/**
 * Product (SPEC.md §7.5).
 *
 * Stateless: state in, callbacks out. [onEnquire] receives the price **currently on
 * screen**, which is what `seenPrice` carries — captured at the tap rather than re-read
 * later, so the email's drift note compares what the customer saw against what the
 * backend holds instead of comparing the backend with itself.
 */
@Composable
fun ProductScreen(
    state: ProductUiState,
    onEnquire: (price: Double?) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // No title, ever. The headline below is the title, and repeating it would
            // spend the one row that keeps Back reachable. Not elevated either: the hero
            // panel sits directly beneath and a rule would compete with its edge.
            CuratoTopBar(title = null, onBack = onBack, elevated = false)

            Box(modifier = Modifier.weight(1f)) {
                when (state) {
                    is ProductUiState.Loading -> ProductPlaceholder()
                    is ProductUiState.Failed -> ProductError(state.error, onRetry, onBack)
                    is ProductUiState.Content -> ProductDetail(state.product)
                }
            }

            // Composed from the first frame, disabled until there is something to enquire
            // about. Arriving late would move the button under a thumb already travelling
            // toward it. Dropped entirely on failure — see `showActionBar`.
            if (state.showActionBar) {
                StickyActionBar {
                    PrimaryButton(
                        label = "Enquire",
                        onClick = { onEnquire(state.displayedPrice) },
                        enabled = state.canEnquire,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductDetail(product: Product) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Clears the sticky bar, so the last line of a long description is readable
            // rather than parked underneath it.
            .padding(bottom = Spacing.stickyBarClearance),
    ) {
        HeroPanel(imageUrl = product.imageUrl, title = product.title)

        Column(
            modifier = Modifier.padding(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = Spacing.gutter,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            Text(
                text = product.category.name.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = EyebrowTracking,
                ),
                color = MaterialTheme.colorScheme.primary,
            )

            Text(
                text = product.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            PriceLine(price = product.price, currency = product.currency)

            Text(
                text = "★ ${oneDecimal(product.rating.rate)} · ${product.rating.count} ratings",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFeatureSettings = TABULAR_FIGURES,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = product.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.x1),
            )
        }
    }
}

/**
 * The price, with its currency stated rather than assumed.
 *
 * The code sits beside the amount because the device's locale has no say in what a
 * catalogue is priced in — a USD product must not render with a rupee symbol on an
 * Indian phone.
 */
@Composable
private fun PriceLine(price: Double, currency: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatPrice(price, currency),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFeatureSettings = TABULAR_FIGURES,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = currency,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.x2, bottom = Spacing.x1),
        )
    }
}

@Composable
private fun HeroPanel(imageUrl: String, title: String) {
    Box(
        modifier = Modifier
            .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x3)
            .fillMaxWidth()
            .aspectRatio(HeroAspectRatio)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = imageUrl,
            // Named here, unlike on a card: there the title beside it already labels the
            // tap target, and describing the image too would read the product name twice.
            contentDescription = title,
            // Aspect ratios run 0.58 to 1.92 and the panel is square-ish; cropping the
            // widest to fit would discard nearly half the product.
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(Spacing.x4),
        ) {
            when (painter.state.collectAsState().value) {
                is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                // The panel is already a visible surface, so a failed image simply leaves
                // it empty rather than stacking a second grey box on a grey box.
                is AsyncImagePainter.State.Error -> Unit
                else -> ShimmerSection {
                    ShimmerBox(
                        modifier = Modifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.large,
                    )
                }
            }
        }
    }
}

/** One decimal for a 0–5 rating; two would imply precision upstream does not have. */
private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10.0).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Loading: the shape of what is arriving, and nothing that claims to be content.
 *
 * One `ShimmerSection` for the whole screen, so the hero and the text lines sweep on a
 * single clock instead of drifting out of phase with each other.
 */
@Composable
private fun ProductPlaceholder() {
    ShimmerSection {
        Column(modifier = Modifier.fillMaxSize()) {
            ShimmerBox(
                modifier = Modifier
                    .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x3)
                    .fillMaxWidth()
                    .aspectRatio(HeroAspectRatio),
                shape = MaterialTheme.shapes.large,
            )
            Column(
                modifier = Modifier.padding(
                    start = Spacing.gutter,
                    end = Spacing.gutter,
                    top = Spacing.gutter,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                TextLine(widthFraction = 0.35f, height = 10.dp)   // category eyebrow
                TextLine(widthFraction = 1f, height = 20.dp)      // title, line one
                TextLine(widthFraction = 0.75f, height = 20.dp)   // title, line two
                TextLine(widthFraction = 0.4f, height = 24.dp)    // price
                TextLine(widthFraction = 0.45f, height = 12.dp)   // rating
            }
        }
    }
}

@Composable
private fun TextLine(widthFraction: Float, height: Dp) {
    // Boxed so the fraction has a full-width parent to resolve against.
    Box(modifier = Modifier.fillMaxWidth()) {
        ShimmerBox(
            modifier = Modifier.fillMaxWidth(widthFraction).height(height),
            shape = MaterialTheme.shapes.extraSmall,
        )
    }
}

/**
 * A failure, full screen and with no action bar.
 *
 * `NOT_FOUND` is terminal, so it gets Back alone — the id will not start resolving, and
 * a button certain to fail is worse than no button. The split comes from
 * [DomainError.isRetryable] rather than a second list of codes kept here.
 */
@Composable
private fun ProductError(error: DomainError, onRetry: () -> Unit, onBack: () -> Unit) {
    val gone = error.code == ErrorCode.NOT_FOUND
    val back = PanelAction(
        label = if (gone) "Back to browsing" else "Go back",
        onClick = onBack,
        primary = gone,
    )

    StatePanel(
        glyph = if (gone) "🗂" else "⚠",
        title = if (gone) "This product is no longer available" else "Could not load this product",
        body = if (gone) {
            "It may have sold out or been removed from the catalogue."
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
