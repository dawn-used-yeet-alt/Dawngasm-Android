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
import dev.dawngasm.app.ui.theme.DawngasmTheme

/**
 * Opens hotaudio.net in a real WebView so the user can pass Cloudflare's
 * challenge. The resulting `cf_clearance` cookie is bound to the WebView's
 * User-Agent and client IP family (IPv4 vs IPv6), so both are saved together
 * and handed to the native extractor.
 */
class HotaudioClearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DawngasmTheme {
                HotaudioClearanceScreen(
                    onCapture = { webView, ip, family -> captureClearance(webView, ip, family) },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun captureClearance(webView: WebView?, ip: String?, family: String?): Boolean {
        HotaudioLog.i("captureClearance: begin ip=$ip family=$family")
        val cookieManager = CookieManager.getInstance()
        val cookieApex = cookieManager.getCookie(HOTAUDIO_URL).orEmpty()
        val cookieWww = cookieManager.getCookie(HOTAUDIO_WWW_URL).orEmpty()
        val rawCookie = listOf(cookieApex, cookieWww)
            .filter { it.isNotBlank() }
            .flatMap { it.split("; ") }
            .distinct()
            .joinToString("; ")
        HotaudioLog.i(
            "captureClearance: cookieApexLen=${cookieApex.length} " +
                "cookieWwwLen=${cookieWww.length} mergedLen=${rawCookie.length} " +
                "hasCfClearance=${rawCookie.contains("cf_clearance", ignoreCase = true)}",
        )
        if (!rawCookie.contains("cf_clearance", ignoreCase = true)) {
            HotaudioLog.w("captureClearance: no cf_clearance cookie yet")
            return false
        }
        val userAgent = webView?.settings?.userAgentString
            ?: WebSettings.getDefaultUserAgent(this)
        HotaudioLog.i("captureClearance: userAgent=${userAgent.take(120)}")
        val prefs = AppPreferences(this)
        prefs.saveHotaudioClearance(rawCookie, userAgent)
        HotaudioLog.i("captureClearance: saved cookie+ua")
        if (family != null) {
            prefs.saveHotaudioIpFamily(family, ip)
            try {
                net.hotaudio.extractor.setIpFamily(family)
                HotaudioLog.i("captureClearance: setIpFamily($family) OK")
            } catch (error: Throwable) {
                HotaudioLog.e("captureClearance: setIpFamily($family) failed", error)
            }
        } else {
            HotaudioLog.w("captureClearance: no IP family detected")
        }
        try {
            net.hotaudio.extractor.setClearance(rawCookie, userAgent)
            HotaudioLog.i("captureClearance: setClearance OK")
        } catch (error: Throwable) {
            HotaudioLog.e("captureClearance: setClearance failed", error)
        }
        cookieManager.flush()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CONNECTED, true))
        finish()
        return true
    }

    companion object {
        const val EXTRA_CONNECTED = "hotaudio_connected"
        private const val HOTAUDIO_URL = "https://hotaudio.net/"
        private const val HOTAUDIO_WWW_URL = "https://www.hotaudio.net/"
    }
}

private class ClearanceBridge(
    private val onTrace: (String) -> Unit,
) {
    @JavascriptInterface
    fun reportTrace(text: String) {
        onTrace(text)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HotaudioClearanceScreen(
    onCapture: (WebView?, String?, String?) -> Boolean,
    onClose: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var detectedIp by remember { mutableStateOf<String?>(null) }
    var detectedFamily by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        val cookieManager = CookieManager.getInstance()
        val cookieApex = cookieManager.getCookie("https://hotaudio.net/").orEmpty()
        val cookieWww = cookieManager.getCookie("https://www.hotaudio.net/").orEmpty()
        connected = cookieApex.contains("cf_clearance", ignoreCase = true) ||
            cookieWww.contains("cf_clearance", ignoreCase = true)
    }

    val handler = remember { Handler(Looper.getMainLooper()) }
    val bridge = remember {
        ClearanceBridge { trace ->
            val ip = trace.lineSequence()
                .firstOrNull { it.startsWith("ip=") }
                ?.substringAfter("ip=")
                ?.trim()
            HotaudioLog.i("webview trace ip=$ip")
            if (!ip.isNullOrBlank()) {
                val fam = if (ip.contains(":")) "ipv6" else "ipv4"
                HotaudioLog.i("webview detected client family=$fam")
                handler.post {
                    detectedIp = ip
                    detectedFamily = fam
                }
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
                    text = "hotaudio check",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (connected) {
                        val ipInfo = detectedIp?.let { " (${detectedFamily?.uppercase() ?: "IP"}: $it)" }.orEmpty()
                        "Verified$ipInfo — tap Done to save"
                    } else {
                        "Pass the Cloudflare check, then tap Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    if (!onCapture(webView, detectedIp, detectedFamily)) {
                        connected = false
                        Toast.makeText(
                            webView?.context,
                            "Cloudflare has not cleared yet — finish the check first",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
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
                        addJavascriptInterface(bridge, "ClearanceBridge")
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
                                HotaudioLog.i("webview page finished url=$url")
                                refreshStatus()
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            fetch('/cdn-cgi/trace')
                                                .then(function(r) { return r.text(); })
                                                .then(function(t) {
                                                    if (window.ClearanceBridge) {
                                                        window.ClearanceBridge.reportTrace(t);
                                                    }
                                                })
                                                .catch(function(e) {});
                                        } catch(e) {}
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
                                    "webview error url=${request?.url} " +
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
                        loadUrl("https://hotaudio.net/")
                        webView = this
                    }
                },
                update = { webView = it },
            )
        }

        Text(
            text = "The cookie is stored on this device only and expires on its own.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}
