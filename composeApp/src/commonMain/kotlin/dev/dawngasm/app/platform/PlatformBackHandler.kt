package dev.dawngasm.app.platform

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform back gesture. Android delegates to the activity's
 * back dispatcher; iOS has no system back button and is a no-op until the
 * Phase 6 gesture pass.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
