package com.curato.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The sweep phase, shared across a section.
 *
 * The kit is specific that one phase is shared per section so placeholders move
 * together. Giving each box its own clock makes a loading shelf look like static, which
 * reads as broken rather than pending.
 */
private val LocalShimmerPhase = compositionLocalOf { 0f }

/** Wrap a loading section so every [ShimmerBox] inside it sweeps on one clock. */
@Composable
fun ShimmerSection(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.SHIMMER_SWEEP_MS, easing = LinearEasing),
        ),
        label = "shimmerPhase",
    )
    CompositionLocalProvider(LocalShimmerPhase provides phase) { content() }
}

/**
 * A placeholder that holds its space while content loads.
 *
 * Decorative by definition, so it carries no semantics — a screen reader announcing
 * eight grey rectangles is worse than silence.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = CuratoShapes.medium,
) {
    val colors = LocalCuratoExtraColors.current
    val phase = LocalShimmerPhase.current
    var width by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .clip(shape)
            .onSizeChanged { width = it.width.toFloat() }
            .background(colors.shimmer)
            .background(
                // The sweep travels from fully off the left edge to fully off the right,
                // so the highlight enters and leaves rather than popping at the bounds.
                Brush.linearGradient(
                    colors = listOf(colors.shimmer, colors.shimmerHighlight, colors.shimmer),
                    start = Offset(width * (phase * 2f - 1f), 0f),
                    end = Offset(width * (phase * 2f), 0f),
                ),
            ),
    )
}
