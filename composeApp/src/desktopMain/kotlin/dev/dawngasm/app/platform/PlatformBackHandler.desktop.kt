package dev.dawngasm.app.platform

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture yet. Phase 11 maps Escape/Backspace. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
