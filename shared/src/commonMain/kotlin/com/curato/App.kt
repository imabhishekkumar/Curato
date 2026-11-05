package com.curato

import androidx.compose.runtime.Composable
import com.curato.ui.designsystem.CuratoTheme
import com.curato.ui.navigation.CuratoNavHost

/**
 * The shared entry point. Each platform hosts this and adds nothing of its own.
 */
@Composable
fun App() {
    CuratoTheme {
        CuratoNavHost()
    }
}
