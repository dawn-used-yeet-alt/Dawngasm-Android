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
