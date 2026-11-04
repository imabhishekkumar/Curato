package com.curato.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing, shape and motion, from the Curato Screen Kit's Metrics sheet.
 */

/** A 4dp base. Every gap in the app is one of these, never an ad-hoc number. */
object Spacing {
    val x1: Dp = 4.dp
    val x2: Dp = 8.dp
    val x3: Dp = 12.dp
    val x4: Dp = 16.dp
    val x6: Dp = 24.dp
    val x8: Dp = 32.dp
    val x12: Dp = 48.dp

    /** Screen gutter — everywhere, including inside cards. */
    val gutter: Dp = x4

    /** Between cards in a row or a grid. */
    val betweenCards: Dp = x3

    /** Between sections on Home. */
    val betweenSections: Dp = x6

    /** Bottom padding on any scroll under a sticky bar, so the last item clears it. */
    val stickyBarClearance: Dp = 88.dp
}

/**
 * Corner radii.
 *
 * The kit is specific about where `full` goes: buttons and the search bar, which are the
 * only two things tapped to go somewhere. Everything else is squarer than it is round.
 */
object Radius {
    val checkbox: Dp = 4.dp
    /** Chips, banners, the trace block. */
    val small: Dp = 8.dp
    /** Text fields and thumbnails. */
    val field: Dp = 10.dp
    /** Cards. */
    val card: Dp = 12.dp
    val panel: Dp = 16.dp
    val full: Dp = 999.dp
}

internal val CuratoShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.checkbox),
    small = RoundedCornerShape(Radius.small),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.panel),
    extraLarge = RoundedCornerShape(Radius.panel),
)

/**
 * Durations.
 *
 * The splash has none of these by design: no branding animation and no timed delay, so
 * it hands off the moment Home's first frame is ready (SPEC.md §7.1).
 */
object Motion {
    /** State swaps within a screen, button enable. */
    const val STATE_SWAP_MS = 150

    /** Route transitions. */
    const val ROUTE_MS = 300

    /**
     * One shimmer sweep. A single clock is shared across a section so its placeholders
     * move together rather than each animating on its own phase.
     */
    const val SHIMMER_SWEEP_MS = 1400
}

/** The minimum touch target, enforced in these components rather than per screen. */
val MinTouchTarget: Dp = 48.dp
