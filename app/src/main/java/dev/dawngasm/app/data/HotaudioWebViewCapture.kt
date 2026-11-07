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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Background WebView-assisted audio streaming and capture for hotaudio.net.
 *
 * Runs the site's official web player in an off-screen WebView, transparently
 * passing Cloudflare and executing the player's native WebAssembly/JS decryptor.
 * Intercepts the decrypted ISO BMFF (fMP4) chunks from SourceBuffer.prototype.appendBuffer,
 * accelerates playback in silence at 2x, streams chunks progressively to ExoPlayer via
 * [HotaudioStreamServer] for instant playback, and concurrently assembles the full track
 * into a cached .m4a file.
 */
class HotaudioWebViewCapture(private val context: Context) {

    companion object {
        private const val TAG = "HotaudioCapture"
        private const val BRIDGE_NAME = "HotaudioBridge"

        private val HOOK_SCRIPT = """
            (function() {
                if (window.__dawngasm_capture_hooked) return;
                window.__dawngasm_capture_hooked = true;

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

                // 1. Hook SourceBuffer.prototype.appendBuffer
                if (typeof SourceBuffer !== 'undefined' && SourceBuffer.prototype && SourceBuffer.prototype.appendBuffer) {
                    var origAppend = SourceBuffer.prototype.appendBuffer;
                    SourceBuffer.prototype.appendBuffer = function(buf) {
                        send('SourceBuffer', buf);
                        return origAppend.apply(this, arguments);
                    };
                }

                // 2. Hook MediaSource.prototype.addSourceBuffer instance attachment
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

                // 3. Hook ManagedSourceBuffer if present
                if (typeof ManagedSourceBuffer !== 'undefined' && ManagedSourceBuffer.prototype && ManagedSourceBuffer.prototype.appendBuffer) {
                    var origManaged = ManagedSourceBuffer.prototype.appendBuffer;
                    ManagedSourceBuffer.prototype.appendBuffer = function(buf) {
                        send('ManagedSourceBuffer', buf);
                        return origManaged.apply(this, arguments);
                    };
                }

                // 4. Hook AudioDecoder if present
                if (typeof AudioDecoder !== 'undefined' && AudioDecoder.prototype && AudioDecoder.prototype.decode) {
                    var origDecode = AudioDecoder.prototype.decode;
                    AudioDecoder.prototype.decode = function(chunk) {
                        try {
                            if (chunk && chunk.copyTo && chunk.byteLength) {
                                var u = new Uint8Array(chunk.byteLength);
                                chunk.copyTo(u);
                                send('AudioDecoder', u);
                            }
                        } catch (e) {}
                        return origDecode.apply(this, arguments);
                    };
                }

                // 5. Audio acceleration and silence (max 2x to avoid CDN throttling)
                function accelerate(media) {
                    if (!media) return;
                    try {
                        media.muted = true;
                        media.volume = 0;
                        var target = 2.0;
                        if (media.playbackRate !== target) {
                            media.playbackRate = target;
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

                // 6. Periodic watcher for play button, rate control, and end of playback
                setInterval(function() {
                    // Check Cloudflare challenge
                    if (document.title && document.title.indexOf('Just a moment') !== -1) {
                        if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.onChallenge) {
                            window.$BRIDGE_NAME.onChallenge();
                        }
                    }

                    // Auto-click paused button
                    var playSelectors = ['#player-playpause', '.player-playpause', 'button[aria-label*="Play" i]', '.play-btn', 'button.play', '#play'];
                    for (var s = 0; s < playSelectors.length; s++) {
                        var btn = document.querySelector(playSelectors[s]);
                        if (btn && (btn.className || '').indexOf('paused') !== -1) {
                            try { btn.click(); } catch(e) {}
                        }
                    }

                    // Accelerate and play all media elements
                    var audios = document.querySelectorAll('audio, video');
                    for (var i = 0; i < audios.length; i++) {
                        var a = audios[i];
                        accelerate(a);
                        if (a.paused) {
                            try {
                                var p = a.play();
                                if (p && p.catch) p.catch(function() {});
                            } catch (e) {}
                        }
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

                    // Read progress display if any
                    var prog = document.getElementById('player-progress-text');
                    if (prog && prog.innerText) {
                        var parts = prog.innerText.split('/');
                        if (parts.length === 2 && window.$BRIDGE_NAME && window.$BRIDGE_NAME.onProgressText) {
                            window.$BRIDGE_NAME.onProgressText(parts[0].trim(), parts[1].trim());
                        }
                    }
                }, 300);
            })();
        """.trimIndent()
    }

    val store = HotaudioMp4.PartStore()
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

    /**
     * Starts progressive streaming for [pageUrl].
     *
     * Launches the local stream server and the background WebView, waits for the
     * init segment (typically 1-2 seconds), and returns a local HTTP URL
     * (`http://127.0.0.1:<port>/stream.m4a`) that ExoPlayer can play immediately.
     */
    suspend fun startStreaming(pageUrl: String, targetFile: File): String {
        val server = HotaudioStreamServer(store)
        val port = server.start()
        streamServer = server

        withContext(Dispatchers.Main) {
            setupWebView(pageUrl, targetFile)
        }

        // Wait up to 25s for the initialization segment (ftyp/moov)
        val init = store.waitForInit(timeoutMs = 25_000)
        if (init == null) {
            cancel()
            if (isChallengeDetected) {
                throw ResolveException("hotaudio Cloudflare clearance expired or invalid — refresh in Settings")
            }
            throw ResolveException("hotaudio stream timed out waiting for audio initialization")
        }

        return "http://127.0.0.1:$port/stream.m4a"
    }

    /**
     * Resolves and downloads audio for [pageUrl] into [targetFile], waiting until
     * the complete file is assembled.
     */
    suspend fun captureToFile(pageUrl: String, targetFile: File): File =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                setupWebView(pageUrl, targetFile, onComplete = { file ->
                    continuation.resume(file)
                }, onError = { message ->
                    continuation.resumeWithException(ResolveException(message))
                })

                continuation.invokeOnCancellation {
                    cancel()
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(
        pageUrl: String,
        targetFile: File,
        onComplete: ((File) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        lastFragmentTime = System.currentTimeMillis()

        fun finishSuccess() {
            if (finished) return
            finished = true
            store.markComplete()
            val result = store.assemble(targetFile)
            cleanupWebView()
            if (result.success && targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "Successfully assembled ${result.fragmentCount} fragments into ${targetFile.length()} bytes")
                onComplete?.invoke(targetFile)
            } else {
                onComplete?.invoke(targetFile)
            }
        }

        fun finishError(message: String) {
            if (finished) return
            finished = true
            store.markFailed()
            cleanupWebView()
            streamServer?.stop()
            onError?.invoke(message)
        }

        watchdog = object : Runnable {
            override fun run() {
                if (finished) return
                val now = System.currentTimeMillis()
                val idleMs = now - lastFragmentTime

                // Check challenge
                if (isChallengeDetected && idleMs > 5000 && store.fragmentCount() == 0) {
                    finishError("hotaudio Cloudflare clearance expired or invalid — refresh in Settings")
                    return
                }

                // Check if playback ended by duration or idle completion
                if (store.hasInit() && store.fragmentCount() > 0) {
                    val isEndedByTime = totalDuration > 0 && currentPosition >= totalDuration - 0.5
                    val isEndedByIdle = idleMs > 8000 && store.checkGaps().isEmpty()
                    if (isEndedByTime || isEndedByIdle) {
                        finishSuccess()
                        return
                    }
                }

                // Stall detection
                if (store.fragmentCount() > 0 && idleMs > 45_000) {
                    if (store.checkGaps().isEmpty()) {
                        finishSuccess()
                    } else {
                        finishError("hotaudio stream stalled — please check your internet connection")
                    }
                    return
                }

                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(watchdog!!, 1000)

        class CaptureBridge {
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
            fun onProgressText(cur: String, tot: String) {
                Log.v(TAG, "Progress text: $cur / $tot")
            }

            @JavascriptInterface
            fun onEnded() {
                Log.i(TAG, "Playback ended event received from page")
                if (store.hasInit() && store.fragmentCount() > 0 && store.checkGaps().isEmpty()) {
                    handler.post { finishSuccess() }
                }
            }

            @JavascriptInterface
            fun onChallenge() {
                isChallengeDetected = true
            }

            @JavascriptInterface
            fun onLog(msg: String) {
                Log.d(TAG, "Page log: $msg")
            }
        }

        val wv = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                databaseEnabled = true
                val savedUa = AppPreferences(context).hotaudioClearance()?.second
                userAgentString = savedUa ?: WebSettings.getDefaultUserAgent(context)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(CaptureBridge(), BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(HOOK_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(HOOK_SCRIPT, null)
                    if (view?.title?.contains("Just a moment", ignoreCase = true) == true) {
                        isChallengeDetected = true
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.w(TAG, "WebView error $errorCode: $description for $failingUrl")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val code = errorResponse?.statusCode ?: 0
                    if (code == 429) {
                        Log.w(TAG, "HTTP 429 received in WebView")
                    } else if (code == 403) {
                        Log.w(TAG, "HTTP 403 received in WebView")
                    }
                }
            }
        }
        webView = wv

        var cleanUrl = pageUrl.trim()
        if (cleanUrl.startsWith("http://", ignoreCase = true)) {
            cleanUrl = "https" + cleanUrl.substring(4)
        }
        cleanUrl = cleanUrl.replace("://www.hotaudio.net", "://hotaudio.net")

        Log.i(TAG, "Loading hotaudio track in background WebView: $cleanUrl")
        wv.loadUrl(cleanUrl)
    }

    private fun cleanupWebView() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
        try {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeJavascriptInterface(BRIDGE_NAME)
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView cleanup error", e)
        }
        webView = null
    }

    fun cancel() {
        finished = true
        store.markFailed()
        cleanupWebView()
        streamServer?.stop()
        streamServer = null
    }
}
