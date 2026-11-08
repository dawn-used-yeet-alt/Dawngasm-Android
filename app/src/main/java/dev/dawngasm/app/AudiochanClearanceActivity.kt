package dev.dawngasm.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.ui.theme.DawngasmTheme

/**
 * Opens audiochan.com in a WebView to let the user pass the Cloudflare
 * Turnstile playback verification check. The resulting clearance cookie
 * and User-Agent are saved for OkHttp playback and downloads.
 */
class AudiochanClearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DawngasmTheme {
                AudiochanClearanceScreen(
                    onCapture = { webView -> captureClearance(webView) },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun captureClearance(webView: WebView?): Boolean {
        HotaudioLog.i("captureAudiochanClearance: begin")
        val cookieManager = CookieManager.getInstance()
        val cookieApex = cookieManager.getCookie(AUDIOCHAN_URL).orEmpty()
        val cookieApi = cookieManager.getCookie(AUDIOCHAN_API_URL).orEmpty()
        val rawCookie = listOf(cookieApex, cookieApi)
            .filter { it.isNotBlank() }
            .flatMap { it.split("; ") }
            .distinct()
            .joinToString("; ")

        val hasClearanceCookie = rawCookie.contains("__Secure-audiochan_playback", ignoreCase = true)
        HotaudioLog.i(
            "captureAudiochanClearance: cookieApexLen=${cookieApex.length} " +
                "cookieApiLen=${cookieApi.length} mergedLen=${rawCookie.length} " +
                "hasClearance=$hasClearanceCookie",
        )

        val userAgent = webView?.settings?.userAgentString
            ?: WebSettings.getDefaultUserAgent(this)

        val prefs = AppPreferences(this)
        prefs.saveAudiochanClearance(rawCookie, userAgent)
        Network.syncCookie(AUDIOCHAN_URL, rawCookie)
        Network.syncCookie(AUDIOCHAN_API_URL, rawCookie)
        cookieManager.flush()
        HotaudioLog.i("captureAudiochanClearance: saved clearance")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CONNECTED, true))
        finish()
        return true
    }

    companion object {
        const val EXTRA_CONNECTED = "audiochan_connected"
        private const val AUDIOCHAN_URL = "https://audiochan.com/"
        private const val AUDIOCHAN_API_URL = "https://api.audiochan.com/"
    }
}

internal class AudiochanBridge(
    private val onVerified: () -> Unit,
) {
    @JavascriptInterface
    fun reportVerified() {
        onVerified()
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun AudiochanClearanceScreen(
    onCapture: (WebView?) -> Boolean,
    onClose: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var verified by remember { mutableStateOf(false) }

    fun refreshStatus() {
        val cookieManager = CookieManager.getInstance()
        val cookieApex = cookieManager.getCookie("https://audiochan.com/").orEmpty()
        val cookieApi = cookieManager.getCookie("https://api.audiochan.com/").orEmpty()
        if (cookieApex.contains("__Secure-audiochan_playback", ignoreCase = true) ||
            cookieApi.contains("__Secure-audiochan_playback", ignoreCase = true)
        ) {
            verified = true
        }
    }

    val handler = remember { Handler(Looper.getMainLooper()) }
    val bridge: AudiochanBridge = remember {
        AudiochanBridge {
            handler.post {
                verified = true
                Toast.makeText(
                    webView?.context,
                    "Verification complete! Saving clearance...",
                    Toast.LENGTH_SHORT,
                ).show()
                onCapture(webView)
            }
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "audiochan check",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (verified) {
                        "Verified — tap Done to save"
                    } else {
                        "Pass the verification check, then tap Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    onCapture(webView)
                },
            ) {
                Text("Done")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        addJavascriptInterface(bridge, "AudiochanBridge")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                HotaudioLog.i("audiochan webview finished url=$url")
                                refreshStatus()
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        if (window.__dawngasm_injected) return;
                                        window.__dawngasm_injected = true;

                                        function setupWidget() {
                                            if (!window.turnstile) {
                                                var s = document.createElement('script');
                                                s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
                                                s.async = true;
                                                s.onload = renderWidget;
                                                document.head.appendChild(s);
                                            } else {
                                                renderWidget();
                                            }
                                        }

                                        function renderWidget() {
                                            var container = document.getElementById('dawngasm-verify-box');
                                            if (!container) {
                                                container = document.createElement('div');
                                                container.id = 'dawngasm-verify-box';
                                                container.style.cssText = 'position:fixed;top:16px;left:50%;transform:translateX(-50%);z-index:999999;background:#181825;color:#cdd6f4;padding:20px;border-radius:16px;box-shadow:0 8px 32px rgba(0,0,0,0.55);text-align:center;max-width:92%;width:340px;font-family:system-ui,-apple-system,sans-serif;border:1px solid #45475a;';
                                                container.innerHTML = '<div style="font-weight:600;font-size:16px;margin-bottom:6px;">Playback Verification</div><div style="font-size:13px;color:#a6adc8;margin-bottom:14px;">Complete the check below to enable streaming on audiochan.</div><div id="dawngasm-turnstile-target" style="display:flex;justify-content:center;"></div><div id="dawngasm-verify-msg" style="margin-top:10px;font-size:12px;color:#89b4fa;"></div>';
                                                document.body.appendChild(container);
                                            }

                                            var target = document.getElementById('dawngasm-turnstile-target');
                                            if (target && window.turnstile) {
                                                try {
                                                    window.turnstile.render(target, {
                                                        sitekey: '0x4AAAAAAAzFFy-NNEmM0ZDp',
                                                        action: 'audio_playback',
                                                        callback: function(token) {
                                                            var msg = document.getElementById('dawngasm-verify-msg');
                                                            if (msg) msg.innerText = 'Verifying with audiochan...';
                                                            fetch('https://api.audiochan.com/audios/playback-verification', {
                                                                method: 'POST',
                                                                headers: {
                                                                    'Content-Type': 'application/json',
                                                                    'Accept': 'application/json'
                                                                },
                                                                credentials: 'include',
                                                                body: JSON.stringify({ turnstile_token: token })
                                                            })
                                                            .then(function(r) { return r.json(); })
                                                            .then(function(data) {
                                                                if (data && data.success) {
                                                                    if (msg) msg.innerText = 'Verified! Saving clearance...';
                                                                    if (window.AudiochanBridge) {
                                                                        window.AudiochanBridge.reportVerified();
                                                                    }
                                                                } else {
                                                                    if (msg) msg.innerText = 'Verification failed, please retry.';
                                                                }
                                                            })
                                                            .catch(function(err) {
                                                                if (msg) msg.innerText = 'Network error: ' + err;
                                                            });
                                                        }
                                                    });
                                                } catch(e) {}
                                            }
                                        }

                                        if (document.readyState === 'complete' || document.readyState === 'interactive') {
                                            setupWidget();
                                        } else {
                                            window.addEventListener('DOMContentLoaded', setupWidget);
                                        }
                                    })();
                                    """.trimIndent(),
                                    null,
                                )
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                HotaudioLog.w(
                                    "audiochan webview error url=${request?.url} " +
                                        "mainFrame=${request?.isForMainFrame} " +
                                        "code=${error?.errorCode} desc=${error?.description}",
                                )
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false
                        }
                        loadUrl("https://audiochan.com/")
                        webView = this
                    }
                },
                update = { webView = it },
            )
        }

        Text(
            text = "The clearance session is saved on this device only and refreshes as needed.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}
