package dev.dawngasm.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * Phase 0 placeholder for the embedded browser used by clearance flows.
 * Phase 10 replaces this with the cross-platform `hotaudio-webview` helper.
 */
@Composable
actual fun ClearanceWebView(
    url: String,
    modifier: Modifier,
    onLoadingChange: (Boolean) -> Unit,
    onVerified: () -> Unit,
    onClearanceCaptured: (cookie: String, userAgent: String) -> Unit,
    captureSignal: Int,
) {
    LaunchedEffect(url, captureSignal) {
        onLoadingChange(false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "Clearance window is coming in a later phase.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
