package dev.dawngasm.app.data

import dev.dawngasm.app.util.PlatformLog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop-only streamer wiring.
 *
 * Every download attempt writes a fresh `.part` file. On Windows a retry must
 * never truncate or delete a file that the local HTTP server still has open,
 * so the old attempt is simply abandoned and cleaned up later.
 */
object HotaudioDesktopIntegration {
    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            HotaudioStreamer.attemptPathProvider = { spec, generation ->
                val dir = spec.filePath.substringBeforeLast('/', "")
                if (dir.isEmpty()) {
                    "${spec.trackId}.gen$generation.m4a.part"
                } else {
                    "$dir/${spec.trackId}.gen$generation.m4a.part"
                }
            }
            HotaudioStreamer.staleAttemptFileCleaner = ::deleteAbandonedAttempts
            HotaudioStreamer.abandonedAttemptCleaner = { path ->
                runCatching { Files.deleteIfExists(Path.of(path)) }
                Unit
            }
            installed = true
            PlatformLog.i(LOG_TAG, "installed per-attempt hotaudio cache paths")
        }
    }

    private fun deleteAbandonedAttempts(spec: HotaudioStreamSpec, keepPath: String?) {
        val keep = keepPath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        val directory = Path.of(spec.filePath).parent ?: return
        val prefix = "${spec.trackId}.gen"
        val base = Path.of(spec.filePath).toAbsolutePath().normalize()
        if (keep != base) {
            runCatching { Files.deleteIfExists(base) }
        }
        runCatching {
            if (!Files.isDirectory(directory)) return
            Files.list(directory).use { entries ->
                entries
                    .filter { path ->
                        val name = path.fileName.toString()
                        name.startsWith(prefix) && name.endsWith(".m4a.part")
                    }
                    .forEach { path ->
                        if (keep != null && path.toAbsolutePath().normalize() == keep) return@forEach
                        runCatching { Files.deleteIfExists(path) }
                            .onFailure {
                                PlatformLog.w(LOG_TAG, "could not delete stale attempt $path: ${it.message}")
                            }
                    }
            }
        }.onFailure {
            PlatformLog.w(LOG_TAG, "stale attempt cleanup failed in $directory: ${it.message}")
        }
    }

    private const val LOG_TAG = "HotaudioDesktop"
}
