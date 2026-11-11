package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.safeFileName
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

data class SavedDownload(
    val uri: Uri,
    val displayName: String,
)

/**
 * Streams a resolved remote file into either the user's SAF folder or the
 * app's own Music/Dawngasm directory. SAF is used instead of broad storage
 * permissions; the tree URI is persisted after the system picker returns.
 */
class TrackDownloader(
    private val context: Context,
) {
    private val fileSaver = AndroidFileSaver(context)

    suspend fun download(
        track: Track,
        audio: ResolvedAudio,
        treeUri: String?,
    ): SavedDownload = withContext(Dispatchers.IO) {
        val fileName = buildFileName(track, audio.extension)
        if (track.host == AudioHost.HOTAUDIO || audio.url.startsWith("${HotaudioStreamer.SCHEME}:")) {
            val spec = HotaudioStreamer.spec(context, track.id, track.pageUrl)
            if (HotaudioStreamer.stateFor(track.id, context) == null) {
                HotaudioLog.i("download hotaudio: stream was not started, starting it now")
                HotaudioStreamer.ensureStarted(spec, AppPreferences(context))
            }
            HotaudioLog.i("download hotaudio: awaiting full stream track=${track.id}")
            val file = File(HotaudioStreamer.awaitComplete(spec))
            HotaudioLog.i(
                "download hotaudio: stream complete track=${track.id} " +
                    "bytes=${file.length()} path=${file.absolutePath}",
            )
            if (treeUri != null) {
                saveToTree(treeUri, fileName, audio.extension, file.inputStream())
            } else {
                saveToAppStorage(fileName, file.inputStream())
            }
        } else {
            val request = Request.Builder()
                .url(audio.url)
                .apply { audio.headers.forEach { (name, value) -> header(name, value) } }
                .header("User-Agent", Network.BROWSER_USER_AGENT)
                .build()

            Network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadException("server returned HTTP ${response.code}")
                }
                val body = response.body ?: throw DownloadException("the server sent an empty file")
                if (treeUri != null) {
                    saveToTree(treeUri, fileName, audio.extension, body.byteStream())
                } else {
                    saveToAppStorage(fileName, body.byteStream())
                }
            }
        }
    }

    fun folderLabel(treeUri: String?): String = fileSaver.folderLabel(treeUri)

    fun exists(localUri: String?): Boolean = fileSaver.exists(localUri)

    private fun saveToTree(
        treeUri: String,
        fileName: String,
        extension: String,
        input: java.io.InputStream,
    ): SavedDownload {
        val tree = DocumentFile.fromTreeUri(context, treeUri.toUri())
            ?: throw DownloadException("the selected folder is no longer available")
        if (!tree.canWrite()) {
            throw DownloadException("the selected folder is read-only")
        }
        val uniqueName = uniqueName(tree, fileName)
        val document = tree.createFile(mimeType(extension), uniqueName)
            ?: throw DownloadException("could not create the file in the selected folder")
        try {
            context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                input.use { it.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
            } ?: throw DownloadException("could not open the selected file for writing")
        } catch (error: Exception) {
            runCatching { document.delete() }
            throw error
        }
        return SavedDownload(document.uri, document.name ?: uniqueName)
    }

    private fun saveToAppStorage(fileName: String, input: java.io.InputStream): SavedDownload {
        val mediaRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val folder = File(mediaRoot, "Dawngasm").apply { mkdirs() }
        val file = uniqueFile(folder, fileName)
        try {
            FileOutputStream(file).use { output ->
                input.use { it.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
            }
        } catch (error: Exception) {
            runCatching { file.delete() }
            throw error
        }
        return SavedDownload(Uri.fromFile(file), file.name)
    }

    private fun uniqueName(tree: DocumentFile, desired: String): String {
        if (tree.findFile(desired) == null) return desired
        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        var index = 2
        while (true) {
            val candidate = "$base ($index)$suffix"
            if (tree.findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private fun uniqueFile(folder: File, desired: String): File {
        var candidate = File(folder, desired)
        if (!candidate.exists()) return candidate
        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        var index = 2
        while (true) {
            candidate = File(folder, "$base ($index)$suffix")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private fun buildFileName(track: Track, extension: String): String {
        val base = safeFileName(
            "${track.displayAuthor} - ${track.title}",
            fallback = "dawngasm-${track.id}",
        )
        return "$base.${extension.ifBlank { "m4a" }}"
    }

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        else -> "audio/mp4"
    }
}

class DownloadException(message: String) : Exception(message)
