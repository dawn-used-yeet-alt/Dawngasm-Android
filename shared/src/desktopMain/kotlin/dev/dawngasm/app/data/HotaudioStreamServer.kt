package dev.dawngasm.app.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.dawngasm.app.util.PlatformLog
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.Volatile

/**
 * Loopback HTTP server that exposes a [HotaudioStreamer] attempt as a normal
 * URL for VLC.
 *
 * The upstream fragments are MP4, so VLC can start after the first fragment.
 * While a stream is still downloading the initial response is sent chunked, so
 * the player keeps reading as the file grows instead of treating the current
 * length as the end. Snapshots (seeks) return a range of the downloaded prefix.
 */
object HotaudioStreamServer {
    private const val CONTENT_TYPE = "audio/mp4"
    private const val CHUNK_SIZE = 256 * 1024
    private const val POLL_MS = 50L
    private const val FIRST_BYTES_TIMEOUT_MS = 120_000L
    private const val STREAM_PATH = "/stream/"

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var executor: ExecutorService? = null

    /** Returns a playable URL, starting the loopback server on first use. */
    @Synchronized
    fun urlFor(trackId: String): String {
        val active = ensureStarted()
        return "http://127.0.0.1:${active.address.port}$STREAM_PATH$trackId"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop(0) }
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun ensureStarted(): HttpServer {
        server?.let { return it }

        val created = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        } catch (error: Throwable) {
            throw ResolveException("Could not start the local hotaudio server: ${error.message}")
        }
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "hotaudio-stream").apply { isDaemon = true }
        }
        created.executor = pool
        created.createContext("/stream") { exchange -> handleSafely(exchange) }
        created.start()
        server = created
        executor = pool
        PlatformLog.i(LOG_TAG, "listening on 127.0.0.1:${created.address.port}")
        return created
    }

    private fun handleSafely(exchange: HttpExchange) {
        try {
            exchange.use(::handle)
        } catch (error: IOException) {
            PlatformLog.d(LOG_TAG, "client disconnected: ${error.message}")
        } catch (error: Throwable) {
            PlatformLog.e(LOG_TAG, "request failed: ${error.message}", error)
        }
    }

    private fun handle(exchange: HttpExchange) {
        val trackId = trackIdFrom(exchange.requestURI.path.orEmpty())
        if (trackId == null) {
            sendError(exchange, 404, "unknown hotaudio stream")
            return
        }
        val state = HotaudioStreamer.stateFor(trackId)
        if (state == null) {
            sendError(exchange, 404, "hotaudio stream is not available")
            return
        }

        when (exchange.requestMethod.uppercase()) {
            "HEAD" -> sendHead(exchange, state)
            "GET" -> serve(exchange, state)
            else -> {
                exchange.responseHeaders.add("Allow", "GET, HEAD")
                sendError(exchange, 405, "method not allowed")
            }
        }
    }

    private fun trackIdFrom(path: String): String? {
        if (!path.startsWith(STREAM_PATH)) return null
        val id = path.removePrefix(STREAM_PATH).substringBefore('/')
        if (id.isEmpty()) return null
        if (!id.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) return null
        return id
    }

    private fun sendHead(exchange: HttpExchange, state: HotaudioStreamState) {
        val total = currentLength(state)
        exchange.responseHeaders.add("Content-Type", CONTENT_TYPE)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        if (total > 0L) {
            exchange.responseHeaders.add("Content-Length", total.toString())
        }
        exchange.sendResponseHeaders(200, -1)
    }

    private fun serve(exchange: HttpExchange, state: HotaudioStreamState) {
        if (!awaitFirstBytes(state)) {
            sendError(exchange, 503, "hotaudio stream produced no data")
            return
        }

        val range = parseRange(exchange.requestHeaders.getFirst("Range"))
        if (state.done) {
            serveComplete(exchange, state, range)
            return
        }

        val requestedStart = range?.start
        if (requestedStart != null && requestedStart > 0L) {
            serveSnapshot(exchange, state, requestedStart)
        } else {
            serveLive(exchange, state)
        }
    }

    /** Finished stream: normal 200/206 with an exact length and seek support. */
    private fun serveComplete(exchange: HttpExchange, state: HotaudioStreamState, range: ByteRange?) {
        val total = currentLength(state)
        if (total <= 0L) {
            sendError(exchange, 404, "hotaudio stream is empty")
            return
        }

        val start: Long
        val end: Long
        when {
            range == null -> {
                start = 0L
                end = total - 1
            }
            range.suffixLength != null -> {
                start = (total - range.suffixLength).coerceAtLeast(0L)
                end = total - 1
            }
            else -> {
                start = range.start ?: 0L
                end = (range.endInclusive ?: (total - 1)).coerceAtMost(total - 1)
            }
        }
        if (start >= total || end < start) {
            exchange.responseHeaders.add("Content-Range", "bytes */$total")
            sendError(exchange, 416, "requested range is outside the stream")
            return
        }

        exchange.responseHeaders.add("Content-Type", CONTENT_TYPE)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        if (range != null) {
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
        }
        val length = end - start + 1
        exchange.sendResponseHeaders(if (range != null) 206 else 200, length)
        copyRange(exchange.responseBody, state.filePath, start, length)
    }

    /** Seek inside the downloaded prefix while the download is still running. */
    private fun serveSnapshot(exchange: HttpExchange, state: HotaudioStreamState, start: Long) {
        val available = awaitAvailablePast(state, start)
        if (available <= start) {
            sendError(exchange, 416, "requested range is outside the downloaded prefix")
            return
        }

        val end = available - 1L
        exchange.responseHeaders.add("Content-Type", CONTENT_TYPE)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/*")
        val length = available - start
        exchange.sendResponseHeaders(206, length)
        copyRange(exchange.responseBody, state.filePath, start, length)
    }

    /**
     * Live playback: no Content-Length, so VLC reads until the download is
     * finished instead of stopping at the current end of the growing file.
     */
    private fun serveLive(exchange: HttpExchange, state: HotaudioStreamState) {
        exchange.responseHeaders.add("Content-Type", CONTENT_TYPE)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, 0L)

        val output = exchange.responseBody
        var position = 0L
        val startGeneration = state.generation
        while (true) {
            val generation = state.generation
            if (generation != startGeneration) {
                PlatformLog.d(LOG_TAG, "stream generation changed; closing live response track=${state.spec.trackId}")
                return
            }

            // Read the path after the generation check and verify it did not
            // change underneath us; a retry swaps both fields.
            val path = state.filePath
            if (state.generation != generation) continue

            val available = maxOf(PlatformFileOps.fileLength(path), state.bytesWritten)
            if (position < available) {
                val chunk = readChunk(path, position, CHUNK_SIZE)
                if (chunk == null) {
                    sleep(POLL_MS)
                    continue
                }
                output.write(chunk)
                output.flush()
                position += chunk.size
                continue
            }

            if (state.done) {
                PlatformLog.d(LOG_TAG, "live response finished track=${state.spec.trackId} bytes=$position")
                return
            }
            if (state.error != null) {
                PlatformLog.w(LOG_TAG, "live response ended after stream failure track=${state.spec.trackId}")
                return
            }
            sleep(POLL_MS)
        }
    }

    private fun awaitFirstBytes(state: HotaudioStreamState): Boolean {
        val deadline = System.currentTimeMillis() + FIRST_BYTES_TIMEOUT_MS
        while (true) {
            if (currentLength(state) > 0L) return true
            if (state.done && state.error != null) return false
            if (state.done && currentLength(state) == 0L) return false
            if (System.currentTimeMillis() > deadline) return false
            sleep(POLL_MS)
        }
    }

    private fun awaitAvailablePast(state: HotaudioStreamState, start: Long): Long {
        val deadline = System.currentTimeMillis() + FIRST_BYTES_TIMEOUT_MS
        while (true) {
            val available = currentLength(state)
            if (available > start) return available
            if (state.done || state.error != null) return available
            if (System.currentTimeMillis() > deadline) return available
            sleep(POLL_MS)
        }
    }

    private fun currentLength(state: HotaudioStreamState): Long =
        maxOf(PlatformFileOps.fileLength(state.filePath), state.bytesWritten)

    private fun copyRange(output: OutputStream, path: String, start: Long, length: Long) {
        RandomAccessFile(path, "r").use { file ->
            file.seek(start)
            val buffer = ByteArray(CHUNK_SIZE)
            var remaining = length
            while (remaining > 0L) {
                val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.flush()
        }
    }

    /** Opens per chunk so a retry can replace the file on Windows. */
    private fun readChunk(path: String, position: Long, max: Int): ByteArray? = runCatching {
        RandomAccessFile(path, "r").use { file ->
            val end = file.length()
            if (position >= end) return@use null
            file.seek(position)
            val size = minOf(max.toLong(), end - position).toInt()
            val buffer = ByteArray(size)
            val read = file.read(buffer)
            when {
                read <= 0 -> null
                read == size -> buffer
                else -> buffer.copyOf(read)
            }
        }
    }.getOrNull()

    private fun sendError(exchange: HttpExchange, status: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        runCatching {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private data class ByteRange(
        val start: Long?,
        val endInclusive: Long?,
        val suffixLength: Long?,
    )

    private fun parseRange(header: String?): ByteRange? {
        val value = header?.trim().orEmpty()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.substringAfter('=').substringBefore(',').trim()
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()

        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
            return ByteRange(start = null, endInclusive = null, suffixLength = suffix)
        }

        val start = startText.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val end = endText.takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (end != null && end < start) return null
        return ByteRange(start = start, endInclusive = end, suffixLength = null)
    }

    private const val LOG_TAG = "HotaudioStreamServer"
}
