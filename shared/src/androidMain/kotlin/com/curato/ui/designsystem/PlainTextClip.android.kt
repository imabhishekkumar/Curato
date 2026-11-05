package com.curato.ui.designsystem

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

/**
 * The label is what Android shows in its own "copied" toast and in clipboard history, so
 * it says what the thing is rather than repeating the value.
 */
internal actual fun plainTextClipEntry(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("Curato trace id", text))
