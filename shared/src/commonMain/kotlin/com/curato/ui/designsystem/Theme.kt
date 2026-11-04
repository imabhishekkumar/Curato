package com.curato.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The app's single theme entry point.
 *
 * Visual decisions live here and in the files beside it, never in a screen — the brief
 * expects look and feel to change, and this is the seam that makes that a token edit
 * rather than a sweep through six screens.
 */
@Composable
fun CuratoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCuratoExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) CuratoDarkColors else CuratoLightColors,
            typography = curatoTypography(),
            shapes = CuratoShapes,
            content = content,
        )
    }
}
