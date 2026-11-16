package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.PlatformLog
import dev.dawngasm.app.util.safeFileName
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
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
            val temp = createTempFile(directory)

            try {
                val fileName = buildFileName(directory, track, audio.extension)
                val target = uniqueTarget(directory, fileName)

                if (
                    track.host == AudioHost.HOTAUDIO ||
                    audio.url.startsWith("${HotaudioStreamer.SCHEME}:")
                ) {
                    val spec = HotaudioStreamer.spec(track.id, track.pageUrl)
                    val stream = HotaudioStreamer.ensureStarted(spec, prefs)
                    val completed = Path.of(HotaudioStreamer.awaitComplete(stream.spec))
                    ensureFreeSpace(directory, Files.size(completed))
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
                        ensureFreeSpace(directory, body.contentLength())
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
            } catch (error: AccessDeniedException) {
                throw IllegalStateException("Permission denied writing to $directory", error)
            } catch (error: FileSystemException) {
                throw IllegalStateException(
                    "Could not write to $directory: ${error.reason ?: error.message ?: "filesystem error"}",
                    error,
                )
            } finally {
                runCatching { Files.deleteIfExists(temp) }
            }
        }

    private fun createTempFile(directory: Path): Path = try {
        Files.createDirectories(directory)
        Files.createTempFile(directory, "dawngasm-", ".part")
    } catch (error: AccessDeniedException) {
        throw IllegalStateException("Permission denied writing to $directory", error)
    } catch (error: FileSystemException) {
        throw IllegalStateException(
            "Could not write to $directory: ${error.reason ?: error.message ?: "filesystem error"}",
            error,
        )
    }

    private fun buildFileName(directory: Path, track: Track, extension: String): String {
        val normalizedExtension = extension
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(MAX_EXTENSION_LENGTH)
            .ifBlank { "m4a" }
        val suffix = ".$normalizedExtension"
        val base = safeFileName(
            input = if (track.author.isNotBlank()) "${track.title} - ${track.author}" else track.title,
            fallback = track.id,
            maxLength = maxBaseLength(directory, suffix),
        )
        return "$base$suffix"
    }

    /**
     * Keeps the full path under the classic Windows 260-character limit. The
     * budget leaves room for the extension and a ` (123)` collision suffix.
     */
    private fun maxBaseLength(directory: Path, suffix: String): Int {
        if (DesktopPlatform.os != DesktopPlatform.Os.WINDOWS) return MAX_FILE_BASE_LENGTH
        val pathLength = directory.toAbsolutePath().toString().length
        val budget = MAX_WINDOWS_PATH - pathLength - 1 - suffix.length - COLLISION_SUFFIX_RESERVE
        return budget.coerceIn(MIN_FILE_BASE_LENGTH, MAX_FILE_BASE_LENGTH)
    }

    private fun uniqueTarget(directory: Path, fileName: String): Path {
        // macOS/Windows file systems are usually case-insensitive; listing the
        // directory makes collisions deterministic even on case-sensitive
        // volumes or providers.
        val caseInsensitive = DesktopPlatform.os != DesktopPlatform.Os.LINUX
        val taken: Set<String> = if (caseInsensitive) {
            runCatching {
                Files.list(directory).use { stream ->
                    stream.map { it.fileName.toString().lowercase() }.toList().toSet()
                }
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }

        fun isTaken(name: String): Boolean =
            Files.exists(directory.resolve(name)) || (caseInsensitive && name.lowercase() in taken)

        if (!isTaken(fileName)) return directory.resolve(fileName)

        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var index = 2
        while (true) {
            val next = "$base ($index)$extension"
            if (!isTaken(next)) return directory.resolve(next)
            index += 1
        }
    }

    /** Fails early with a readable message instead of a half-written file. */
    private fun ensureFreeSpace(directory: Path, contentLength: Long) {
        if (contentLength <= 0) return
        val usable = runCatching { Files.getFileStore(directory).usableSpace }.getOrNull() ?: return
        val required = contentLength + FREE_SPACE_HEADROOM
        if (usable in 1L until required) {
            throw IllegalStateException(
                "Not enough free space: need ${formatMegabytes(required)}, have ${formatMegabytes(usable)}",
            )
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return if (megabytes >= 10.0) "${megabytes.toLong()} MB" else "${(megabytes * 10).toInt() / 10.0} MB"
    }

    private companion object {
        const val MAX_FILE_BASE_LENGTH = 110
        const val MIN_FILE_BASE_LENGTH = 32
        const val MAX_EXTENSION_LENGTH = 10
        const val MAX_WINDOWS_PATH = 248
        const val COLLISION_SUFFIX_RESERVE = 10
        const val FREE_SPACE_HEADROOM = 16L * 1024 * 1024
    }
}
