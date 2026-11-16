package dev.dawngasm.app.util

import dev.dawngasm.app.data.DesktopPlatform
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop logging: stdout/stderr for terminal runs plus a rotating UTF-8 log
 * file under the per-OS config directory.
 */
actual object PlatformLog {
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_ROTATIONS = 3

    private val lock = Any()
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private fun logFile(): Path = DesktopPlatform.logsDir.resolve("dawngasm.log")

    actual fun d(tag: String, message: String) {
        write("DEBUG", tag, message, null)
    }

    actual fun i(tag: String, message: String) {
        write("INFO", tag, message, null)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        write("WARN", tag, message, throwable)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        write("ERROR", tag, message, throwable)
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val stackTrace = throwable?.stackTraceText()
        val line = buildString {
            append(OffsetDateTime.now().format(formatter))
            append(' ')
            append(level)
            append(" [")
            append(tag)
            append("] ")
            append(message)
            if (!stackTrace.isNullOrBlank()) {
                append('\n')
                append(stackTrace)
            }
            append('\n')
        }

        if (level == "ERROR" || level == "WARN") {
            System.err.print(line)
        } else {
            print(line)
        }

        synchronized(lock) {
            runCatching {
                Files.createDirectories(DesktopPlatform.logsDir)
                val file = logFile()
                if (Files.exists(file) && Files.size(file) > MAX_LOG_BYTES) {
                    rotate(file)
                }
                Files.writeString(
                    file,
                    line,
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE,
                )
            }
        }
    }

    private fun rotate(file: Path) {
        for (index in MAX_ROTATIONS downTo 1) {
            val older = file.resolveSibling("${file.fileName}.$index")
            if (index == 1) {
                runCatching { Files.move(file, older, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            } else {
                val previous = file.resolveSibling("${file.fileName}.${index - 1}")
                if (Files.exists(previous)) {
                    runCatching {
                        Files.move(previous, older, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun Throwable.stackTraceText(): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printStackTrace(it) }
        return writer.toString()
    }
}
