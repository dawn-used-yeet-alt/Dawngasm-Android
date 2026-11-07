package dev.dawngasm.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Attached WebView media capture and streaming engine for hotaudio.net.
 *
 * Hosted directly in the [MainActivity] view hierarchy so Chromium treats it
 * as an active, visible window without timer throttling or autoplay blocking.
 * Disables AudioContext and sets autoplay to true before site scripts load,
 * captures decrypted fMP4 chunks via SourceBuffer hooks, and streams them
 * progressively through [HotaudioStreamServer] at 2x speed.
 */
class HotaudioWebViewCapture(private val context: Context) {

    companion object {
        private const val TAG = "HotaudioCapture"
        private const val BRIDGE_NAME = "HotaudioBridge"

        private val HOOK_SCRIPT = """
            (function() {
                if (window.__dawngasm_capture_hooked) return;
                window.__dawngasm_capture_hooked = true;

                // 1. Disable AudioContext so nozzle.js falls back to direct HTML5 Audio
                // without getting blocked by WebAudio autoplay policy
                try {
                    window.AudioContext = undefined;
                    window.webkitAudioContext = undefined;
                } catch(e) {}

                // 2. Prevent background throttling of timers and MediaSource
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch(e) {}

                // 3. Hook window.__HAL so nozzle.js automatically autoplays when initialized
                var _origHAL = window.__HAL;
                try {
                    Object.defineProperty(window, '__HAL', {
                        get: function() { return _origHAL; },
                        set: function(fn) {
                            _origHAL = function(w, v, A, M, L) {
                                return fn(true, v, A, M, L);
                            };
                        },
                        configurable: true
                    });
                } catch(e) {}

                function send(sink, buf) {
                    try {
                        if (!buf) return;
                        var u;
                        if (buf instanceof ArrayBuffer) {
                            u = new Uint8Array(buf);
                        } else if (buf.buffer instanceof ArrayBuffer) {
                            u = new Uint8Array(buf.buffer, buf.byteOffset || 0, buf.byteLength);
                        } else if (buf instanceof Uint8Array) {
                            u = buf;
                        } else {
                            return;
                        }
                        if (u.length < 8) return;

                        var s = '';
                        var CHUNK = 0x8000;
                        for (var i = 0; i < u.length; i += CHUNK) {
                            var slice = u.subarray(i, Math.min(i + CHUNK, u.length));
                            s += String.fromCharCode.apply(null, slice);
                        }
                        var b64 = btoa(s);
                        if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.onBuffer) {
                            window.$BRIDGE_NAME.onBuffer(sink, b64);
                        }
                    } catch (e) {
                        if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.onLog) {
                            window.$BRIDGE_NAME.onLog('send error: ' + e.message);
                        }
                    }
                }

                // 4. Hook SourceBuffer.prototype.appendBuffer
                if (typeof SourceBuffer !== 'undefined' && SourceBuffer.prototype && SourceBuffer.prototype.appendBuffer) {
                    var origAppend = SourceBuffer.prototype.appendBuffer;
                    SourceBuffer.prototype.appendBuffer = function(buf) {
                        send('SourceBuffer', buf);
                        return origAppend.apply(this, arguments);
                    };
                }

                // 5. Hook MediaSource.prototype.addSourceBuffer
                if (typeof MediaSource !== 'undefined' && MediaSource.prototype && MediaSource.prototype.addSourceBuffer) {
                    var origAddSourceBuffer = MediaSource.prototype.addSourceBuffer;
                    MediaSource.prototype.addSourceBuffer = function() {
                        var sb = origAddSourceBuffer.apply(this, arguments);
                        if (sb && sb.appendBuffer && !sb.__dawngasm_hooked) {
                            sb.__dawngasm_hooked = true;
                            var origInstanceAppend = sb.appendBuffer;
                            sb.appendBuffer = function(buf) {
                                send('SourceBufferInstance', buf);
                                return origInstanceAppend.apply(this, arguments);
                            };
                        }
                        return sb;
                    };
                }

                // 6. Hook ManagedSourceBuffer if present
                if (typeof ManagedSourceBuffer !== 'undefined' && ManagedSourceBuffer.prototype && ManagedSourceBuffer.prototype.appendBuffer) {
                    var origManaged = ManagedSourceBuffer.prototype.appendBuffer;
                    ManagedSourceBuffer.prototype.appendBuffer = function(buf) {
                        send('ManagedSourceBuffer', buf);
                        return origManaged.apply(this, arguments);
                    };
                }

                // 7. Audio acceleration (max 2.0x to avoid CDN throttling) and silence
                function accelerate(media) {
                    if (!media) return;
                    try {
                        media.muted = true;
                        media.volume = 0;
                        if (media.playbackRate !== 2.0) {
                            media.playbackRate = 2.0;
                        }
                    } catch (e) {}
                }

                if (typeof HTMLMediaElement !== 'undefined' && HTMLMediaElement.prototype && HTMLMediaElement.prototype.play) {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        accelerate(this);
                        return origPlay.apply(this, arguments);
                    };
                }

                // 8. Hook Audio constructor to capture new Audio()
                if (typeof window.Audio !== 'undefined') {
                    var OrigAudio = window.Audio;
                    window.Audio = function() {
                        var a = new OrigAudio();
                        accelerate(a);
                        return a;
                    };
                    window.Audio.prototype = OrigAudio.prototype;
                }

                // 9. Periodic watcher for challenge and play button
                setInterval(function() {
                    // Check Cloudflare
                    var isChallenge = (document.title && document.title.indexOf('Just a moment') !== -1) ||
                                      document.getElementById('challenge-running') !== null ||
                                      document.querySelector('.cf-turnstile') !== null;
                    if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.onChallenge) {
                        window.$BRIDGE_NAME.onChallenge(isChallenge);
                    }

                    // Auto-click paused play button
                    var playSelectors = ['#player-playpause', '.player-playpause', 'button[aria-label*="Play" i]', '.play-btn', 'button.play', '#play'];
                    for (var s = 0; s < playSelectors.length; s++) {
                        var btn = document.querySelector(playSelectors[s]);
                        if (btn && (btn.className || '').indexOf('paused') !== -1) {
                            try { btn.click(); } catch(e) {}
                        }
                    }

                    // Progress update
                    var audios = document.querySelectorAll('audio, video');
                    for (var i = 0; i < audios.length; i++) {
                        var a = audios[i];
                        accelerate(a);
                        if (a.duration && a.duration > 0) {
                            if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.onProgress) {
                                window.$BRIDGE_NAME.onProgress(a.currentTime, a.duration);
                            }
                            if (a.ended || a.currentTime >= a.duration - 0.2) {
                                if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.onEnded) {
                                    window.$BRIDGE_NAME.onEnded();
                                }
                            }
                        }
                    }
                }, 250);
            })();
        """.trimIndent()
    }

    var store = HotaudioMp4.PartStore()
        private set

    private val _challengeUrl = MutableStateFlow<String?>(null)
    val challengeUrl: StateFlow<String?> = _challengeUrl.asStateFlow()

    private var streamServer: HotaudioStreamServer? = null
    private var webView: WebView? = null
    private var watchdog: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var finished = false
    @Volatile
    private var isChallengeDetected = false
    @Volatile
    private var lastFragmentTime = System.currentTimeMillis()
    @Volatile
    private var totalDuration = -1.0
    @Volatile
    private var currentPosition = -1.0
    @Volatile
    private var currentTrackUrl: String? = null

    fun dismissChallenge() {
        _challengeUrl.value = null
        cancel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(activityContext: Context): WebView {
        webView?.let { return it }

        val wv = WebView(activityContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                val savedUa = AppPreferences(context).hotaudioClearance()?.second
                userAgentString = savedUa ?: WebSettings.getDefaultUserAgent(context)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(CaptureBridge(), BRIDGE_NAME)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, HOOK_SCRIPT, setOf("*"))
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(HOOK_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(HOOK_SCRIPT, null)
                    checkClearance()
                    if (view?.title?.contains("Just a moment", ignoreCase = true) == true) {
                        isChallengeDetected = true
                        _challengeUrl.value = currentTrackUrl
                    }
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.w(TAG, "WebView error $errorCode: $description for $failingUrl")
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val code = errorResponse?.statusCode ?: 0
                    if (code == 429) {
                        Log.w(TAG, "HTTP 429 received in WebView")
                    } else if (code == 403) {
                        Log.w(TAG, "HTTP 403 received in WebView")
                        if (request?.isForMainFrame == true) {
                            isChallengeDetected = true
                            _challengeUrl.value = currentTrackUrl
                        }
                    }
                }
            }
        }
        webView = wv
        return wv
    }

    private fun checkClearance() {
        val cm = CookieManager.getInstance()
        val apex = cm.getCookie("https://hotaudio.net/").orEmpty()
        val www = cm.getCookie("https://www.hotaudio.net/").orEmpty()
        val raw = "$apex; $www"
        if (raw.contains("cf_clearance", ignoreCase = true)) {
            val ua = webView?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(context)
            AppPreferences(context).saveHotaudioClearance(raw, ua)
            cm.flush()
            if (isChallengeDetected) {
                isChallengeDetected = false
                _challengeUrl.value = null
            }
        }
    }

    /**
     * Starts progressive streaming for [pageUrl] using the attached WebView.
     */
    suspend fun startStreaming(pageUrl: String, targetFile: File): String {
        cancel()
        finished = false
        store = HotaudioMp4.PartStore()
        lastFragmentTime = System.currentTimeMillis()
        totalDuration = -1.0
        currentPosition = -1.0
        currentTrackUrl = pageUrl

        val server = HotaudioStreamServer(store)
        val port = server.start()
        streamServer = server

        withContext(Dispatchers.Main) {
            val wv = getOrCreateWebView(context)
            setupWatchdog(targetFile)

            var cleanUrl = pageUrl.trim()
            if (cleanUrl.startsWith("http://", ignoreCase = true)) {
                cleanUrl = "https" + cleanUrl.substring(4)
            }
            cleanUrl = cleanUrl.replace("://www.hotaudio.net", "://hotaudio.net")

            Log.i(TAG, "Loading hotaudio track in attached WebView: $cleanUrl")
            wv.loadUrl(cleanUrl)
        }

        val init = store.waitForInit(timeoutMs = 30_000)
        if (init == null) {
            if (isChallengeDetected) {
                _challengeUrl.value = currentTrackUrl
                throw ResolveException("hotaudio Cloudflare verification required — complete the check on screen")
            }
            cancel()
            throw ResolveException("hotaudio stream timed out waiting for audio initialization")
        }

        // Init arrived, ensure challenge dialog is dismissed
        _challengeUrl.value = null
        return "http://127.0.0.1:$port/stream.m4a"
    }

    private fun setupWatchdog(targetFile: File) {
        watchdog?.let { handler.removeCallbacks(it) }

        fun finishSuccess() {
            if (finished) return
            finished = true
            store.markComplete()
            val result = store.assemble(targetFile)
            _challengeUrl.value = null
            if (result.success && targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "Assembled complete cache file: ${targetFile.length()} bytes (${result.fragmentCount} fragments)")
            }
        }

        watchdog = object : Runnable {
            override fun run() {
                if (finished) return
                val now = System.currentTimeMillis()
                val idleMs = now - lastFragmentTime

                if (store.hasInit() && store.fragmentCount() > 0) {
                    val isEndedByTime = totalDuration > 0 && currentPosition >= totalDuration - 0.5
                    val isEndedByIdle = idleMs > 8000 && store.checkGaps().isEmpty()
                    if (isEndedByTime || isEndedByIdle) {
                        finishSuccess()
                        return
                    }
                }

                if (store.fragmentCount() > 0 && idleMs > 45_000) {
                    if (store.checkGaps().isEmpty()) {
                        finishSuccess()
                    } else {
                        Log.w(TAG, "Stream stalled after 45s idle")
                    }
                    return
                }

                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(watchdog!!, 1000)
    }

    private inner class CaptureBridge {
        @JavascriptInterface
        fun onBuffer(sink: String, base64Data: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (store.add(bytes)) {
                    lastFragmentTime = System.currentTimeMillis()
                    Log.d(TAG, "Captured $sink buffer: ${bytes.size}B, total fragments=${store.fragmentCount()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding buffer", e)
            }
        }

        @JavascriptInterface
        fun onProgress(current: Double, duration: Double) {
            currentPosition = current
            totalDuration = duration
        }

        @JavascriptInterface
        fun onEnded() {
            Log.i(TAG, "Playback ended event from page")
        }

        @JavascriptInterface
        fun onChallenge(challengeActive: Boolean) {
            isChallengeDetected = challengeActive
            if (challengeActive) {
                _challengeUrl.value = currentTrackUrl
            } else {
                checkClearance()
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            Log.d(TAG, "Page log: $msg")
        }
    }

    fun cancel() {
        finished = true
        store.markFailed()
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
        streamServer?.stop()
        streamServer = null
        handler.post {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
        }
    }
}
