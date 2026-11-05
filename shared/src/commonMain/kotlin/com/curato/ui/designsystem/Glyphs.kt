package com.curato.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The kit's sprite, drawn rather than imported.
 *
 * `material-icons-extended` is some two thousand vectors, and this app shows five. Each
 * of these is the kit's own path data on its 24-unit square, reproduced at whatever size
 * the caller asks for — the same approach `BackChevron` and the search `Magnifier`
 * already take.
 *
 * All of them are **decorative**: every caller sits beside text that says the same thing
 * in words, so none carries a `contentDescription` and announcing them would read
 * punctuation aloud.
 */

/** The kit's `#g-warn`: a triangle, a bar and a dot, 1.8 stroke. */
@Composable
fun WarningGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = unit()
        val stroke = roundStroke(1.8f * unit)
        drawPath(
            path = Path().apply {
                moveTo(12f * unit, 3.5f * unit)
                lineTo(2.5f * unit, 20f * unit)
                lineTo(21.5f * unit, 20f * unit)
                close()
            },
            color = tint,
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(12f * unit, 9.5f * unit),
            end = Offset(12f * unit, 14.5f * unit),
            strokeWidth = 1.8f * unit,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = tint,
            radius = 0.9f * unit,
            center = Offset(12f * unit, 17.4f * unit),
        )
    }
}

/**
 * The kit's `#g-clock`, which it uses for one thing only: `RATE_LIMITED`.
 *
 * A separate glyph from the warning because the two failures want different reactions —
 * "something is wrong" versus "wait a moment" — and the banner's wording alone does not
 * carry that at a glance.
 */
@Composable
fun ClockGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = unit()
        drawCircle(
            color = tint,
            radius = 8.2f * unit,
            center = Offset(12f * unit, 12f * unit),
            style = roundStroke(1.7f * unit),
        )
        drawPath(
            path = Path().apply {
                moveTo(12f * unit, 7.5f * unit)
                lineTo(12f * unit, 12f * unit)
                lineTo(15f * unit, 14f * unit)
            },
            color = tint,
            style = roundStroke(1.7f * unit),
        )
    }
}

/** The kit's `#g-tick`: `m4 12.5 5.5 5.5L20 7`, a heavier 2.4 stroke. */
@Composable
fun TickGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = unit()
        drawPath(
            path = Path().apply {
                moveTo(4f * unit, 12.5f * unit)
                lineTo(9.5f * unit, 18f * unit)
                lineTo(20f * unit, 7f * unit)
            },
            color = tint,
            style = roundStroke(2.4f * unit),
        )
    }
}

/** The kit's `#g-edit`: a single stroked nib path. */
@Composable
fun EditGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = unit()
        drawPath(
            path = Path().apply {
                moveTo(4f * unit, 20f * unit)
                lineTo(8f * unit, 20f * unit)
                lineTo(19.5f * unit, 8.5f * unit)
                // The kit's `a2 2 0 0 0-2.8-2.8` nib, as a cubic: the shape reads the same
                // at 20dp and an arc-to on a Path here would be four more lines of maths
                // for a difference nothing can see.
                cubicTo(
                    20.6f * unit, 7.4f * unit,
                    20.6f * unit, 5.6f * unit,
                    16.7f * unit, 5.7f * unit,
                )
                lineTo(5f * unit, 17f * unit)
                lineTo(5f * unit, 20f * unit)
                close()
            },
            color = tint,
            style = roundStroke(1.8f * unit),
        )
    }
}

/** The kit's `#g-gone`: a card with a header rule and a short line — a removed record. */
@Composable
fun GoneGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = unit()
        val stroke = roundStroke(1.7f * unit)
        drawRoundRect(
            color = tint,
            topLeft = Offset(4f * unit, 6f * unit),
            size = Size(16f * unit, 13f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * unit),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(4f * unit, 10f * unit),
            end = Offset(20f * unit, 10f * unit),
            strokeWidth = 1.7f * unit,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(9.5f * unit, 14.5f * unit),
            end = Offset(14.5f * unit, 14.5f * unit),
            strokeWidth = 1.7f * unit,
            cap = StrokeCap.Round,
        )
    }
}

/** One drawn unit of the kit's 24-unit square, at whatever size the caller asked for. */
private fun DrawScope.unit(): Float = size.minDimension / 24f

private fun roundStroke(width: Float) =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
