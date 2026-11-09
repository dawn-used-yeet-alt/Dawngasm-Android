package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource

/**
 * A [DataSource.Factory] whose request headers can be swapped before each
 * media item. audiochan needs Origin/Referer headers; soundgasm and whyp just
 * need a browser user agent. Sharing the OkHttp client keeps the cookie jar.
 */
@OptIn(UnstableApi::class)
class HeaderDataSourceFactory(
    context: Context,
    private val client: okhttp3.OkHttpClient,
) : DataSource.Factory {
    private val appContext = context.applicationContext

    @Volatile
    var headers: Map<String, String> = emptyMap()

    override fun createDataSource(): DataSource {
        val http = OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(headers)
        return RoutingDataSource(appContext, DefaultDataSource.Factory(appContext, http))
    }
}

/**
 * Sends `hotaudio://stream/...` URIs to [HotaudioDataSource] and everything
 * else to Media3's default file/HTTP stack.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(
    private val context: Context,
    private val fallbackFactory: DataSource.Factory,
) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val next = if (dataSpec.uri.scheme.equals(HotaudioStreamer.SCHEME, ignoreCase = true)) {
            HotaudioLog.i("routing to HotaudioDataSource uri=${dataSpec.uri}")
            HotaudioDataSource(context)
        } else {
            fallbackFactory.createDataSource()
        }
        listeners.forEach(next::addTransferListener)
        delegate = next
        return next.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()
}
