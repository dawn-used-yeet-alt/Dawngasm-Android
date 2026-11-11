package dev.dawngasm.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.dawngasm.app.data.CookieUtils

internal class AndroidAudiochanBridge(
    private val onVerified: () -> Unit,
) {
    @JavascriptInterface
    fun reportVerified() {
        onVerified()
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
actual fun ClearanceWebView(
    url: String,
    modifier: Modifier,
    onLoadingChange: (Boolean) -> Unit,
    onVerified: () -> Unit,
    onClearanceCaptured: (cookie: String, userAgent: String) -> Unit,
    captureSignal: Int,
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    fun doCapture() {
        val webView = webViewRef ?: return
        val cookieManager = CookieManager.getInstance()
        val cookieApex = cookieManager.getCookie("https://audiochan.com/").orEmpty()
        val cookieApi = cookieManager.getCookie("https://api.audiochan.com/").orEmpty()
        val rawCookie = CookieUtils.mergeCookies(cookieApex, cookieApi)
        val userAgent = webView.settings.userAgentString
            ?: WebSettings.getDefaultUserAgent(context)
        onClearanceCaptured(rawCookie, userAgent)
    }

    LaunchedEffect(captureSignal) {
        if (captureSignal > 0) {
            doCapture()
        }
    }

    val bridge = remember {
        AndroidAudiochanBridge {
            handler.post {
                onVerified()
                doCapture()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                addJavascriptInterface(bridge, "AudiochanBridge")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onLoadingChange(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoadingChange(false)
                        val cookieManager = CookieManager.getInstance()
                        val cookieApex = cookieManager.getCookie("https://audiochan.com/").orEmpty()
                        val cookieApi = cookieManager.getCookie("https://api.audiochan.com/").orEmpty()
                        if (CookieUtils.hasCookie(cookieApex, "__Secure-audiochan_playback") ||
                            CookieUtils.hasCookie(cookieApi, "__Secure-audiochan_playback")
                        ) {
                            onVerified()
                        }
                        view?.evaluateJavascript(ClearanceScripts.AUDIOCHAN_TURNSTILE_INJECTION, null)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onLoadingChange(false)
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = false
                }
                loadUrl(url)
                webViewRef = this
            }
        },
        update = { webViewRef = it },
    )
}
