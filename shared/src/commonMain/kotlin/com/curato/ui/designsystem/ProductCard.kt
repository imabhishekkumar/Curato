package com.curato.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

/** The width of a card on a horizontally scrolling shelf. */
val ShelfCardWidth: Dp = 156.dp

/**
 * One product, everywhere a product is listed.
 *
 * `rating` is optional and that is load-bearing: the recently-viewed snapshot is stored
 * on the device and carries no rating, because storing one would put a value on the
 * device that nothing revalidates. The star line is omitted rather than faked.
 */
@Composable
fun ProductCard(
    title: String,
    price: Double,
    currency: String,
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rating: Double? = null,
    ratingCount: Int? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(ShelfCardWidth),
        shape = CuratoShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.gutter)) {
            // A visible surface rather than a placeholder waiting to be covered: images
            // run 0.58 to 1.92 in aspect ratio, so most of them genuinely do not fill a
            // square and the panel is what gives a shelf a consistent rhythm.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CuratoShapes.small),
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    // The card is tappable and labelled by its title; the image repeats
                    // that, so describing it again would make a screen reader say the
                    // product name twice.
                    contentDescription = null,
                    // Never Crop. Cropping the widest product to a square discards 48%
                    // of it, and on a product photographed end to end against white
                    // that removes the product.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(Spacing.x2),
                ) {
                    // The panel holds its size from first layout either way, so a shelf
                    // never reflows as images arrive. The sweep runs only while one is
                    // actually in flight — an animation behind a loaded image is motion
                    // that means nothing.
                    when (painter.state.collectAsState().value) {
                        is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                        is AsyncImagePainter.State.Error ->
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
                        else -> ShimmerSection {
                            ShimmerBox(modifier = Modifier.fillMaxSize(), shape = CuratoShapes.small)
                        }
                    }
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // No fixed height: the layout has to hold at large system font scales,
                // so two lines of 14/20 is a minimum rather than a cage.
                modifier = Modifier
                    .padding(top = Spacing.x3)
                    .heightIn(min = 40.dp),
            )

            Text(
                text = formatPrice(price, currency),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = TABULAR_FIGURES,
                ),
                modifier = Modifier.padding(top = Spacing.x1),
            )

            if (rating != null) {
                RatingLine(
                    rating = rating,
                    count = ratingCount,
                    modifier = Modifier.padding(top = Spacing.x1),
                )
            }
        }
    }
}

@Composable
private fun RatingLine(rating: Double, count: Int?, modifier: Modifier = Modifier) {
    val label = buildString {
        append("★ ")
        append(formatTwoDecimalsPublic(rating))
        if (count != null) append("  ($count)")
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = TABULAR_FIGURES,
            ),
            // Secondary text is a colour role, never a transparency — an alpha on text
            // is what quietly breaks the 4.5:1 contrast the kit requires.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One decimal is enough for a 0–5 rating; two would imply precision upstream lacks. */
private fun formatTwoDecimalsPublic(value: Double): String {
    val tenths = kotlin.math.round(value * 10.0).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
