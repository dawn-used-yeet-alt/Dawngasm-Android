package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads the growing hotaudio file while [HotaudioStreamer] appends fragments.
 *
 * The upstream output is fragmented MP4, so ExoPlayer can start after the first
 * fragment. When a read reaches the current end of the file, this source waits
 * for the downloader to append more instead of reporting end-of-input.
 */
class HotaudioDataSource(private val context: Context) : DataSource {
    private var state: HotaudioStreamState? = null
    private var file: RandomAccessFile? = null
    private var dataSpec: DataSpec? = null
    private var uri: Uri? = null
    private var position = 0L
    private var openedGeneration = -1
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        uri = dataSpec.uri

        // DataSource#open may be called again without close after a seek/retry.
        file?.let { runCatching { it.close() } }
        file = null

        val trackId = HotaudioStreamer.trackIdFrom(dataSpec.uri)
            ?: throw IOException("not a hotaudio stream URI: ${dataSpec.uri}")
        val stream = HotaudioStreamer.stateFor(trackId, context)
            ?: throw IOException("hotaudio stream $trackId is not available")

        state = stream
        position = dataSpec.position

        // A retry can delete the file for a moment while the next family starts.
        var waitedMs = 0L
        while (!stream.spec.file.exists()) {
            stream.error?.let {
                throw IOException("hotaudio stream failed: ${it.message}", it)
            }
            if (stream.done) {
                throw IOException("hotaudio stream $trackId finished without a file")
            }
            if (waitedMs >= 120_000L) {
                throw IOException("timed out waiting for hotaudio file $trackId")
            }
            sleepOrThrow(waitedMs)
            waitedMs += 50L
        }

        file = RandomAccessFile(stream.spec.file, "r")
        openedGeneration = stream.generation

        HotaudioLog.i(
            "datasource open track=$trackId position=$position bytes=${stream.bytesWritten} " +
                "done=${stream.done} family=${stream.family} generation=${stream.generation} " +
                "error=${stream.error?.message}",
        )
        listeners.forEach { it.onTransferStart(this, dataSpec, false) }
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = state ?: return C.RESULT_END_OF_INPUT
        val spec = dataSpec
        var waitedMs = 0L

        while (true) {
            if (state == null) {
                HotaudioLog.d("datasource read after close track=${stream.spec.trackId}")
                return C.RESULT_END_OF_INPUT
            }

            // The downloader deletes and recreates the file between IP-family
            // attempts, so reopen whenever the generation changes.
            if (file == null || openedGeneration != stream.generation) {
                file?.let { runCatching { it.close() } }
                file = null
                if (stream.spec.file.exists()) {
                    try {
                        file = RandomAccessFile(stream.spec.file, "r")
                        openedGeneration = stream.generation
                        HotaudioLog.i(
                            "datasource reopened track=${stream.spec.trackId} " +
                                "generation=${stream.generation}",
                        )
                    } catch (error: IOException) {
                        HotaudioLog.w(
                            "datasource reopen failed track=${stream.spec.trackId}: " +
                                "${error.message}",
                        )
                    }
                }
            }

            val handle = file
            val available = stream.bytesWritten
            if (handle != null && position < available) {
                handle.seek(position)
                val read = handle.read(buffer, offset, length)
                if (read > 0) {
                    position += read
                    if (spec != null) {
                        listeners.forEach { it.onBytesTransferred(this, spec, false, read) }
                    }
                    if (position == available && !stream.done) {
                        HotaudioLog.d(
                            "datasource caught up track=${stream.spec.trackId} " +
                                "position=$position bytes=$available",
                        )
                    }
                    return read
                }
            }

            stream.error?.let {
                throw IOException("hotaudio stream failed: ${it.message}", it)
            }
            if (stream.done) {
                HotaudioLog.i(
                    "datasource EOF track=${stream.spec.trackId} position=$position " +
                        "bytes=$available",
                )
                return C.RESULT_END_OF_INPUT
            }

            if (waitedMs % 5_000L == 0L) {
                HotaudioLog.d(
                    "datasource waiting track=${stream.spec.trackId} position=$position " +
                        "bytes=$available generation=${stream.generation} " +
                        "phase=${stream.progressPhase} " +
                        "${stream.progressLoaded}/${stream.progressTotal}",
                )
            }
            sleepOrThrow(waitedMs)
            waitedMs += 50L
        }
    }

    private fun sleepOrThrow(waitedMs: Long) {
        try {
            Thread.sleep(50L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("hotaudio read interrupted after ${waitedMs}ms", interrupted)
        }
    }

    override fun close() {
        val spec = dataSpec
        val stream = state
        try {
            file?.close()
        } finally {
            file = null
            state = null
            if (spec != null) {
                listeners.forEach { it.onTransferEnd(this, spec, false) }
            }
            HotaudioLog.i(
                "datasource close track=${stream?.spec?.trackId} position=$position",
            )
        }
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
}
