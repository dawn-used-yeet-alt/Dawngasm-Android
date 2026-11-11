package dev.dawngasm.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-rendered WebView for completing browser challenges (e.g. Cloudflare Turnstile).
 *
 * @param url The page URL to load (e.g. `https://audiochan.com/`).
 * @param modifier Composable modifier.
 * @param onLoadingChange Callback when page starts/stops loading.
 * @param onVerified Callback when the challenge script signals successful verification.
 * @param onClearanceCaptured Callback delivering the merged cookies and browser User-Agent.
 * @param captureSignal Incremented whenever the caller wants to force a capture (e.g. "Done" button).
 */
@Composable
expect fun ClearanceWebView(
    url: String,
    modifier: Modifier = Modifier,
    onLoadingChange: (Boolean) -> Unit = {},
    onVerified: () -> Unit = {},
    onClearanceCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
    captureSignal: Int = 0,
)
