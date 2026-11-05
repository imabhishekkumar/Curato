package com.curato.ui.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The palette, lifted from the Curato Screen Kit.
 *
 * One seed, two schemes. Every value here is a token from the kit rather than a choice
 * made in code — changing a colour means changing the kit and copying it down, so the
 * design and the build cannot drift apart.
 */

// --- Light -------------------------------------------------------------------------
private val LightPrimary = Color(0xFF0F5F57)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFBFE5DE)
private val LightOnPrimaryContainer = Color(0xFF00201C)
private val LightSecondaryContainer = Color(0xFFDCE5E2)
private val LightOnSecondaryContainer = Color(0xFF1B2321)
private val LightTertiary = Color(0xFF8A5A00)
private val LightSurface = Color(0xFFFBFCFA)
private val LightOnSurface = Color(0xFF101A19)
private val LightSurfaceContainer = Color(0xFFF1F4F1)
private val LightSurfaceContainerHigh = Color(0xFFE7ECE9)
private val LightSurfaceVariant = Color(0xFFDDE4E1)
private val LightOnSurfaceVariant = Color(0xFF475351)
private val LightOutline = Color(0xFF76827F)
private val LightOutlineVariant = Color(0xFFC3CCC9)
private val LightError = Color(0xFFA3302A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410004)

// --- Dark --------------------------------------------------------------------------
private val DarkPrimary = Color(0xFF8FCFC5)
private val DarkOnPrimary = Color(0xFF003731)
private val DarkPrimaryContainer = Color(0xFF005048)
private val DarkOnPrimaryContainer = Color(0xFFABEBE0)
private val DarkSecondaryContainer = Color(0xFF334240)
private val DarkOnSecondaryContainer = Color(0xFFD4DEDB)
private val DarkTertiary = Color(0xFFE8BE6A)
private val DarkSurface = Color(0xFF0E1716)
private val DarkOnSurface = Color(0xFFDDE4E1)
private val DarkSurfaceContainer = Color(0xFF192322)
private val DarkSurfaceContainerHigh = Color(0xFF232E2C)
private val DarkSurfaceVariant = Color(0xFF3F4B49)
private val DarkOnSurfaceVariant = Color(0xFFBEC9C6)
private val DarkOutline = Color(0xFF89938F)
private val DarkOutlineVariant = Color(0xFF3F4B49)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

internal val CuratoLightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    background = LightSurface,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

internal val CuratoDarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

/**
 * The two kit tokens Material 3 has no role for.
 *
 * Shimmer needs a base and a highlight, and both differ per scheme. Carrying them in the
 * theme rather than hard-coding them in `ShimmerBox` keeps every colour in one file.
 */
@Immutable
data class CuratoExtraColors(
    val shimmer: Color,
    val shimmerHighlight: Color,
)

internal val LightExtraColors = CuratoExtraColors(
    shimmer = Color(0xFF101A19).copy(alpha = 0.055f),
    shimmerHighlight = Color(0xFFFFFFFF).copy(alpha = 0.85f),
)

internal val DarkExtraColors = CuratoExtraColors(
    shimmer = Color(0xFFDEE5E2).copy(alpha = 0.07f),
    shimmerHighlight = Color(0xFFDEE5E2).copy(alpha = 0.09f),
)

val LocalCuratoExtraColors = staticCompositionLocalOf { LightExtraColors }
