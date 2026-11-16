package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.PlatformLog
import dev.dawngasm.app.util.safeFileName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Streams downloads straight to a temp file with OkHttp, then moves the file
 * into place. Downloads no longer load a whole track into memory.
 *
 * Hotaudio streams are already written to the cache by [HotaudioStreamer]; once
 * complete they are copied into the library with the same atomic move.
 */
class DesktopTrackDownloader(
    private val prefs: AppPreferences,
) : TrackDownloader {
    private val fileSaver = DesktopFileSaver()

    override fun folderLabel(treeUri: String?): String = fileSaver.folderLabel(treeUri)

    override fun exists(localUri: String?): Boolean = fileSaver.exists(localUri)

    override suspend fun download(track: Track, audio: ResolvedAudio, treeUri: String?): String =
        withContext(Dispatchers.IO) {
            val directory = DesktopFileSaver.resolveDirectory(treeUri)
            Files.createDirectories(directory)
            val fileName = buildFileName(track, audio.extension)
            val target = uniqueTarget(directory, fileName)
            val temp = Files.createTempFile(directory, "dawngasm-", ".part")

            try {
                if (
                    track.host == AudioHost.HOTAUDIO ||
                    audio.url.startsWith("${HotaudioStreamer.SCHEME}:")
                ) {
                    val spec = HotaudioStreamer.spec(track.id, track.pageUrl)
                    if (HotaudioStreamer.stateFor(track.id) == null) {
                        HotaudioStreamer.ensureStarted(spec, prefs)
                    }
                    val completed = Path.of(HotaudioStreamer.awaitComplete(spec))
                    Files.copy(completed, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else {
                    val request = Request.Builder()
                        .url(audio.url)
                        .header("User-Agent", Network.BROWSER_USER_AGENT)
                        .apply {
                            audio.headers.forEach { (name, value) ->
                                if (!name.equals("User-Agent", ignoreCase = true)) header(name, value)
                            }
                        }
                        .build()

                    Network.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Server returned HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IllegalStateException("Empty response body")
                        body.byteStream().use { input ->
                            Files.newOutputStream(
                                temp,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE,
                            ).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                if (Files.size(temp) == 0L) {
                    throw IllegalStateException("Download produced no data")
                }
                DesktopFs.moveAtomic(temp, target)
                PlatformLog.i(
                    "DesktopTrackDownloader",
                    "downloaded track=${track.id} bytes=${Files.size(target)} target=$target",
                )
                target.toUri().toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                runCatching { Files.deleteIfExists(temp) }
            }
        }

    private fun buildFileName(track: Track, extension: String): String {
        val base = safeFileName(
            input = if (track.author.isNotBlank()) "${track.title} - ${track.author}" else track.title,
            fallback = track.id,
        )
        val normalizedExtension = extension.ifBlank { "m4a" }
        return "$base.$normalizedExtension"
    }

    private fun uniqueTarget(directory: Path, fileName: String): Path {
        val candidate = directory.resolve(fileName)
        if (!Files.exists(candidate)) return candidate
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var index = 2
        while (true) {
            val next = directory.resolve("$base ($index)$extension")
            if (!Files.exists(next)) return next
            index += 1
        }
    }
}
