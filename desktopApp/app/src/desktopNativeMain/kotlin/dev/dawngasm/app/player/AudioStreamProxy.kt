@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dawngasm.app.player

import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.ResolvedAudio
import dev.dawngasm.app.util.PlatformLog
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.concurrent.Volatile
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toUInt
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.IntVar
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.inet_addr
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * Loopback HTTP streaming proxy for audio hosts requiring authentication/anti-hotlinking
 * headers (such as Audiochan).
 *
 * GStreamer's souphttpsrc cannot attach custom request headers (Cookie, Origin, Referer, User-Agent).
 * Rather than blocking playback on a full file download to disk, this proxy streams audio
 * chunks upstream in real time to GStreamer over 127.0.0.1, starting playback in under 200 ms.
 */
object AudioStreamProxy {
    private const val LOG_TAG = "AudioStreamProxy"
    private const val NO_SIGNAL: Int = 0x4000 // MSG_NOSIGNAL on Linux to prevent SIGPIPE on client disconnect

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var streamMap: Map<String, ResolvedAudio> = emptyMap()

    @Volatile
    private var serverFd: Int = -1

    @Volatile
    private var boundPort: Int = 0

    private var acceptJob: Job? = null

    fun streamUrl(trackId: String, audio: ResolvedAudio): String {
        streamMap = streamMap + (trackId to audio)
        ensureStarted()
        return "http://127.0.0.1:$boundPort/stream/$trackId"
    }

    private fun ensureStarted() {
        if (serverFd >= 0 && boundPort > 0) return

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) {
            PlatformLog.e(LOG_TAG, "Failed to create proxy socket")
            return
        }

        val port = memScoped {
            val opt = alloc<IntVar>()
            opt.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, opt.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = 0u
            addr.sin_addr.s_addr = inet_addr("127.0.0.1")

            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                PlatformLog.e(LOG_TAG, "Failed to bind proxy socket")
                close(fd)
                return
            }

            val len = alloc<socklen_tVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            if (getsockname(fd, addr.ptr.reinterpret(), len.ptr) != 0) {
                PlatformLog.e(LOG_TAG, "Failed to get proxy socket name")
                close(fd)
                return
            }

            val raw = addr.sin_port.toUInt()
            (((raw and 0xFFu) shl 8) or (raw shr 8)).toInt()
        }

        if (port <= 0 || listen(fd, 16) != 0) {
            PlatformLog.e(LOG_TAG, "Failed to listen on proxy socket")
            close(fd)
            return
        }

        serverFd = fd
        boundPort = port
        PlatformLog.i(LOG_TAG, "Streaming proxy listening on 127.0.0.1:$boundPort")

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && serverFd >= 0) {
                val clientFd = accept(serverFd, null, null)
                if (clientFd < 0) {
                    if (isActive && serverFd >= 0) {
                        delay(50)
                    }
                    continue
                }
                launch {
                    handleClient(clientFd)
                }
            }
        }
    }

    private suspend fun handleClient(clientFd: Int) {
        try {
            val reqBytes = ByteArray(4096)
            val read = reqBytes.usePinned { pinned ->
                recv(clientFd, pinned.addressOf(0), reqBytes.size.convert(), 0)
            }
            if (read <= 0) return
            val reqString = reqBytes.decodeToString(0, read.toInt())

            val firstLine = reqString.lineSequence().firstOrNull().orEmpty()
            val method = firstLine.substringBefore(' ').trim().uppercase()
            val path = firstLine.split(' ').getOrNull(1).orEmpty()
            val trackId = path.removePrefix("/stream/").substringBefore('?')

            val audio = streamMap[trackId]
            if (audio == null) {
                sendNotFound(clientFd)
                return
            }

            val rangeHeader = reqString.lineSequence()
                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()

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
                }.encodeToByteArray()

                responseHeader.usePinned { pinned ->
                    send(clientFd, pinned.addressOf(0), responseHeader.size.convert(), NO_SIGNAL)
                }

                if (!isHead && response.status.isSuccess()) {
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    buffer.usePinned { pinned ->
                        while (!channel.isClosedForRead) {
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count <= 0) break
                            val sent = send(clientFd, pinned.addressOf(0), count.convert(), NO_SIGNAL)
                            if (sent <= 0) break
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // Cancelled
        } catch (e: Throwable) {
            PlatformLog.d(LOG_TAG, "Client disconnected: ${e.message}")
        } finally {
            close(clientFd)
        }
    }

    private fun sendNotFound(clientFd: Int) {
        val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray()
        resp.usePinned { pinned ->
            send(clientFd, pinned.addressOf(0), resp.size.convert(), NO_SIGNAL)
        }
        close(clientFd)
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        val fd = serverFd
        serverFd = -1
        boundPort = 0
        if (fd >= 0) close(fd)
        streamMap = emptyMap()
    }
}
