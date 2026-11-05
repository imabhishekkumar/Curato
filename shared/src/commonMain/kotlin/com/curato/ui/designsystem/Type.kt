package com.curato.ui.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.curato.resources.Res
import com.curato.resources.inter_variable
import org.jetbrains.compose.resources.Font

/**
 * Inter, with the tabular figures switched on.
 *
 * One variable face covers every weight the kit uses. Sizes and line heights are the
 * kit's values exactly; nothing here is rounded or reinterpreted.
 */
@Composable
fun interFamily(): FontFamily = FontFamily(
    Font(Res.font.inter_variable, FontWeight.Normal),
    Font(Res.font.inter_variable, FontWeight.Medium),
    Font(Res.font.inter_variable, FontWeight.SemiBold),
    Font(Res.font.inter_variable, FontWeight.Bold),
)

/**
 * Tabular figures.
 *
 * Prices sit in vertical lists and in a sticky bar beside a button, so proportional
 * digits make columns of numbers ripple. `tnum` fixes every digit to one advance width.
 */
const val TABULAR_FIGURES = "tnum"

@Composable
internal fun curatoTypography(): Typography {
    val inter = interFamily()
    fun style(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
        fontFamily = inter,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = weight,
    )
    return Typography(
        displaySmall = style(36, 44, FontWeight.SemiBold),
        headlineSmall = style(24, 32, FontWeight.Bold),
        titleLarge = style(18, 24, FontWeight.SemiBold),
        titleMedium = style(16, 24, FontWeight.SemiBold),
        titleSmall = style(14, 20, FontWeight.SemiBold),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(12, 16, FontWeight.Normal),
        labelLarge = style(14, 20, FontWeight.SemiBold),
        labelMedium = style(12, 16, FontWeight.Medium),
        labelSmall = style(11, 16, FontWeight.Medium),
    )
}
