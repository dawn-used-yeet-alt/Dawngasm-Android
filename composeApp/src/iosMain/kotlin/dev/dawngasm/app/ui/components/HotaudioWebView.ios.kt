package dev.dawngasm.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private class IosHotaudioTraceScriptMessageHandler(
    private val onTrace: (String) -> Unit,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        if (didReceiveScriptMessage.name == "clearanceBridge") {
            val body = didReceiveScriptMessage.body as? String ?: ""
            onTrace(body)
        }
    }
}

private class IosHotaudioNavigationDelegate(
    private val onLoading: (Boolean) -> Unit,
    private val onPageFinished: (WKWebView) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onLoading(false)
        onPageFinished(webView)
    }

    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        onLoading(false)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun HotaudioWebView(
    url: String,
    modifier: Modifier,
    onLoadingChange: (Boolean) -> Unit,
    onStatusChange: (hasClearance: Boolean) -> Unit,
    onTraceDetected: (ip: String, family: String) -> Unit,
    onClearanceCaptured: (cookie: String, userAgent: String) -> Unit,
    captureSignal: Int,
) {
    var webViewRef by remember { mutableStateOf<WKWebView?>(null) }

    fun checkStatus(webView: WKWebView) {
        val cookieStore = webView.configuration.websiteDataStore.httpCookieStore
        cookieStore.getAllCookies { cookies ->
            val cookieList = cookies as? List<*> ?: emptyList<Any>()
            val hasClearance = cookieList.filterIsInstance<NSHTTPCookie>().any {
                it.name.equals("cf_clearance", ignoreCase = true)
            }
            onStatusChange(hasClearance)
        }
    }

    fun doCapture(webView: WKWebView?) {
        val view = webView ?: return
        view.evaluateJavaScript("navigator.userAgent") { uaResult, _ ->
            val userAgent = uaResult as? String
                ?: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15"
            val cookieStore = view.configuration.websiteDataStore.httpCookieStore
            cookieStore.getAllCookies { cookies ->
                val cookieList = cookies as? List<*> ?: emptyList<Any>()
                val rawCookie = cookieList
                    .filterIsInstance<NSHTTPCookie>()
                    .map { "${it.name}=${it.value}" }
                    .distinct()
                    .joinToString("; ")
                onClearanceCaptured(rawCookie, userAgent)
            }
        }
    }

    LaunchedEffect(captureSignal) {
        if (captureSignal > 0) {
            doCapture(webViewRef)
        }
    }

    val scriptHandler = remember {
        IosHotaudioTraceScriptMessageHandler { trace ->
            val ip = trace.lineSequence()
                .firstOrNull { it.startsWith("ip=") }
                ?.substringAfter("ip=")
                ?.trim()
            if (!ip.isNullOrBlank()) {
                val family = if (ip.contains(':')) "ipv6" else "ipv4"
                onTraceDetected(ip, family)
            }
        }
    }

    val navDelegate = remember {
        IosHotaudioNavigationDelegate(
            onLoading = onLoadingChange,
            onPageFinished = { view ->
                checkStatus(view)
                view.evaluateJavaScript(ClearanceScripts.HOTAUDIO_TRACE_INJECTION, null)
            },
        )
    }

    UIKitView(
        modifier = modifier,
        factory = {
            val config = WKWebViewConfiguration().apply {
                userContentController.addScriptMessageHandler(scriptHandler, name = "clearanceBridge")
            }
            val webView = WKWebView(
                frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = config,
            )
            webView.navigationDelegate = navDelegate
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl != null) {
                webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
            }
            webViewRef = webView
            webView
        },
        update = { webViewRef = it },
    )
}
