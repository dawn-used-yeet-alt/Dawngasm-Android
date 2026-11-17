package dev.dawngasm.app.player

import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.ResolvedAudio
import dev.dawngasm.app.util.PlatformLog
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Loopback HTTP streaming proxy for audio hosts requiring authentication/anti-hotlinking
 * headers (such as Audiochan).
 *
 * KMPlayer / GStreamer's souphttpsrc cannot attach custom request headers (Cookie, Origin, Referer, User-Agent).
 * Rather than blocking playback on a full file download to disk, this proxy streams audio
 * chunks upstream in real time to the player over 127.0.0.1, starting playback in under 200 ms.
 */
object AudioStreamProxy {
    private const val LOG_TAG = "AudioStreamProxy"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val startMutex = Mutex()

    @Volatile
    private var streamMap: Map<String, ResolvedAudio> = emptyMap()

    @Volatile
    private var boundPort: Int = 0

    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    suspend fun streamUrl(trackId: String, audio: ResolvedAudio): String {
        streamMap = streamMap + (trackId to audio)
        ensureStarted()
        return "http://127.0.0.1:$boundPort/stream/$trackId"
    }

    private suspend fun ensureStarted() {
        if (serverSocket != null && boundPort > 0) return
        startMutex.withLock {
            if (serverSocket != null && boundPort > 0) return

            try {
                val sm = SelectorManager(Dispatchers.IO)
                val server = aSocket(sm).tcp().bind("127.0.0.1", 0)
                val port = (server.localAddress as? InetSocketAddress)?.port ?: 0

                selectorManager = sm
                serverSocket = server
                boundPort = port
                PlatformLog.i(LOG_TAG, "Streaming proxy listening on 127.0.0.1:$boundPort")

                acceptJob = scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        val client = try {
                            server.accept()
                        } catch (_: Throwable) {
                            break
                        }
                        launch {
                            handleClient(client)
                        }
                    }
                }
            } catch (e: Throwable) {
                PlatformLog.e(LOG_TAG, "Failed to start streaming proxy", e)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        try {
            val reqLine = input.readUTF8Line() ?: return
            val parts = reqLine.split(' ')
            val method = parts.getOrNull(0)?.uppercase().orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            val trackId = path.removePrefix("/stream/").substringBefore('?')

            var rangeHeader: String? = null
            while (true) {
                val line = input.readUTF8Line() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
            }

            val audio = streamMap[trackId]
            if (audio == null) {
                output.writeStringUtf8("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                output.flush()
                return
            }

            PlatformLog.d(LOG_TAG, "Streaming track=$trackId method=$method range=$rangeHeader")

            val isHead = method == "HEAD"
            val client = Network.httpClient
            client.prepareGet(audio.url) {
                val ua = audio.headers["User-Agent"] ?: Network.BROWSER_USER_AGENT
                audio.headers.forEach { (name, value) ->
                    if (!name.equals("User-Agent", ignoreCase = true)) {
                        header(name, value)
                    }
                }
                header("User-Agent", ua)
                if (!rangeHeader.isNullOrBlank()) {
                    header("Range", rangeHeader)
                }
                timeout {
                    requestTimeoutMillis = 3_600_000
                    socketTimeoutMillis = 60_000
                }
            }.execute { response ->
                val status = response.status.value
                val statusText = response.status.description.ifBlank {
                    if (status == 206) "Partial Content" else "OK"
                }
                val contentType = response.headers["Content-Type"] ?: "audio/mpeg"
                val contentLength = response.headers["Content-Length"]
                val contentRange = response.headers["Content-Range"]
                val acceptRanges = response.headers["Accept-Ranges"] ?: "bytes"

                val responseHeader = buildString {
                    append("HTTP/1.1 $status $statusText\r\n")
                    append("Content-Type: $contentType\r\n")
                    if (contentLength != null) append("Content-Length: $contentLength\r\n")
                    if (contentRange != null) append("Content-Range: $contentRange\r\n")
                    append("Accept-Ranges: $acceptRanges\r\n")
                    append("Connection: close\r\n\r\n")
                }

                output.writeStringUtf8(responseHeader)
                output.flush()

                if (!isHead && response.status.isSuccess()) {
                    val channel = response.bodyAsChannel()
                    channel.copyTo(output)
                    output.flush()
                }
            }
        } catch (_: CancellationException) {
            // Cancelled
        } catch (e: Throwable) {
            PlatformLog.d(LOG_TAG, "Client disconnected: ${e.message}")
        } finally {
            runCatching { output.flush() }
            runCatching { socket.close() }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { selectorManager?.close() }
        selectorManager = null
        boundPort = 0
        streamMap = emptyMap()
    }
}
