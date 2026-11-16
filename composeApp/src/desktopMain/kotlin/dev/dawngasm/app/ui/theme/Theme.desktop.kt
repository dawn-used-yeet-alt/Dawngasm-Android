package dev.dawngasm.app.ui.theme

import androidx.compose.runtime.Composable

/** Window chrome is owned by Compose Desktop; nothing to theme here. */
@Composable
actual fun PlatformThemeEffect(darkTheme: Boolean) {
    // no-op
}
