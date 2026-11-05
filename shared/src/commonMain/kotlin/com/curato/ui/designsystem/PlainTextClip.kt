package com.curato.ui.designsystem

import androidx.compose.ui.platform.ClipEntry

/**
 * A plain-text clipboard entry, per platform.
 *
 * **The only `expect`/`actual` in the UI layer**, and it exists for a specific gap rather
 * than by preference. Compose Multiplatform 1.12.2 exposes the modern `Clipboard`
 * interface in `commonMain` — `LocalClipboard`, `setClipEntry` — but `ClipEntry` itself is
 * an `expect class` with no common way to build one from a `String`: the `withPlainText`
 * factory is published only in the iOS artifact, and Android's wraps a `ClipData`.
 *
 * The alternative was `LocalClipboardManager.setText`, which *is* in `commonMain` and
 * would have been one line — but it is deprecated in favour of the suspending `Clipboard`,
 * and taking a deprecation to avoid two four-line files is the wrong trade in code written
 * to be read.
 */
internal expect fun plainTextClipEntry(text: String): ClipEntry
