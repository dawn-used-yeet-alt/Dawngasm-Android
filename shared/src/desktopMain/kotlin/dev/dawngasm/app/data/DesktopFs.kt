package dev.dawngasm.app.data

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Shared UTF-8 + atomic-write helpers for desktop files. */
internal object DesktopFs {
    fun writeTextAtomic(path: Path, text: String) {
        writeAtomic(path, text.toByteArray(Charsets.UTF_8))
    }

    fun writeBytesAtomic(path: Path, bytes: ByteArray) {
        writeAtomic(path, bytes)
    }

    /** Moves a fully written temp file into place with the same fallback. */
    fun moveAtomic(source: Path, target: Path) {
        val parent = target.toAbsolutePath().parent ?: Path.of(".")
        Files.createDirectories(parent)
        try {
            moveIntoPlace(source, target)
        } catch (error: Throwable) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(source)
        }
    }

    /** Copies a completed file into place without loading it into memory. */
    fun copyAtomic(source: Path, target: Path) {
        val parent = target.toAbsolutePath().parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "dawngasm-", ".part")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            moveIntoPlace(temp, target)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun writeAtomic(path: Path, bytes: ByteArray) {
        val parent = path.toAbsolutePath().parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "dawngasm-", ".tmp")
        try {
            Files.write(
                temp,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            moveIntoPlace(temp, path)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun moveIntoPlace(temp: Path, target: Path) {
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
