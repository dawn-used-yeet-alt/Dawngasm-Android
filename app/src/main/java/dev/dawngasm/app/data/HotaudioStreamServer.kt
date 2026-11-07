package dev.dawngasm.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight local HTTP stream server bound to loopback (127.0.0.1).
 *
 * Serves arriving fMP4 fragments directly to ExoPlayer/OkHttp as a continuous
 * progressive stream (`Content-Type: audio/mp4`), enabling instant playback
 * without having to wait for the entire track to buffer.
 */
class HotaudioStreamServer(
    private val store: HotaudioMp4.PartStore,
) {
    companion object {
        private const val TAG = "HotaudioStreamServer"
    }

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    @Volatile
    var isRunning: Boolean = false
        private set

    val port: Int get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        isRunning = true
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                try {
                    val client = server.accept()
                    launch { handleClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
        Log.i(TAG, "Stream server started on 127.0.0.1:${server.localPort}")
        return server.localPort
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                val input = s.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return@use
                var line = input.readLine()
                while (!line.isNullOrBlank()) {
                    line = input.readLine()
                }

                val out = s.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/mp4\r\n" +
                    "Accept-Ranges: none\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"

                if (requestLine.startsWith("HEAD")) {
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return@use
                }

                out.write(header.toByteArray(Charsets.US_ASCII))
                out.flush()

                // 1. Wait for and stream the ISO BMFF init segment (ftyp + moov)
                val init = store.waitForInit(timeoutMs = 25_000)
                if (init != null) {
                    out.write(init)
                    out.flush()
                    Log.d(TAG, "Streamed init segment (${init.size}B)")
                } else {
                    Log.w(TAG, "Timed out waiting for init segment")
                    return@use
                }

                // 2. Progressively stream media fragments (moof + mdat) in sequence order
                var nextSeq: Long? = null
                while (isRunning) {
                    if (nextSeq == null) {
                        val first = store.firstSequence(timeoutMs = 20_000)
                        if (first == null) {
                            if (store.isComplete || store.isFailed) break
                            continue
                        }
                        nextSeq = first
                    }

                    val pair = store.getOrWaitFragment(nextSeq, timeoutMs = 20_000)
                    if (pair != null) {
                        val (actualSeq, fragData) = pair
                        out.write(fragData)
                        out.flush()
                        nextSeq = actualSeq + 1
                    } else {
                        if (store.isComplete || store.isFailed) {
                            Log.d(TAG, "Store complete/failed, ending stream")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Client disconnected (e.g. user skipped or closed playback)
                Log.d(TAG, "Client socket finished or closed: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        job?.cancel()
        serverSocket = null
        job = null
        Log.i(TAG, "Stream server stopped")
    }
}
