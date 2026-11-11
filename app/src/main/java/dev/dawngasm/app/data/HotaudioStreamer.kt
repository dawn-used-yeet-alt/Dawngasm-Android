package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

val HotaudioStreamSpec.file: File get() = File(filePath)

fun HotaudioStreamer.spec(context: Context?, trackId: String, pageUrl: String): HotaudioStreamSpec {
    context?.cacheDir?.let { PlatformFileOps.setCacheDir(it.absolutePath) }
    return spec(trackId, pageUrl)
}

fun HotaudioStreamer.streamUri(trackId: String): Uri =
    dev.dawngasm.app.data.HotaudioStreamer.streamUri(trackId).toUri()

fun HotaudioStreamer.trackIdFrom(uri: Uri): String? =
    dev.dawngasm.app.data.HotaudioStreamer.trackIdFrom(uri.toString())

fun HotaudioStreamer.stateFor(trackId: String, context: Context?): HotaudioStreamState? =
    dev.dawngasm.app.data.HotaudioStreamer.stateFor(trackId)
