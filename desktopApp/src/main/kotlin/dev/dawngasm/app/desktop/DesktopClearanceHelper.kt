package dev.dawngasm.app.desktop

import dev.dawngasm.app.data.DesktopPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Spawns the bundled native webview helper (see
 * `desktopApp/native/clearance-helper`) and returns the captured cookies.
 *
 * The helper is a separate process so the JVM never links WebKitGTK, WKWebView,
 * or WebView2. It opens its own native window, lets the user pass the challenge,
 * and prints one JSON response on stdout.
 */
object DesktopClearanceHelper {
    private const val BINARY_NAME = "dawngasm-clearance-helper"
    private const val HELPER_DIR = "clearance-helper"

    data class Captured(val cookie: String, val userAgent: String, val url: String)

    sealed interface Result {
        data class Success(val captured: Captured) : Result
        data class Failure(val code: String, val message: String) : Result
    }

    /** A running helper process; [cancel] closes its window and kills it. */
    class Session {
        @Volatile
        private var process: Process? = null

        fun cancel() {
            process?.let { current ->
                current.destroy()
                if (current.isAlive) current.destroyForcibly()
            }
            process = null
        }

        suspend fun capture(
            url: String,
            cookieUrls: List<String>,
            requiredCookie: String,
            title: String,
            initScript: String? = null,
            timeoutSeconds: Long = 300,
        ): Result = withContext(Dispatchers.IO) {
            val executable = locate() ?: return@withContext Result.Failure(
                "missing",
                "The native clearance helper was not found. Build it with " +
                    "`./gradlew :desktopApp:stageClearanceHelper`, or paste the cookie manually.",
            )

            val request = buildJsonObject {
                put("url", url)
                put("cookie_urls", buildJsonArray { cookieUrls.forEach { add(it) } })
                put("required_cookie", requiredCookie)
                put("title", title)
                put("timeout_seconds", timeoutSeconds)
                if (initScript != null) put("init_script", initScript)
            }.toString()

            val current = try {
                ProcessBuilder(executable.absolutePath)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (error: Exception) {
                return@withContext Result.Failure(
                    "start",
                    "Could not start the clearance helper: ${error.message ?: error::class.simpleName}",
                )
            }
            process = current
            try {
                current.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(request)
                }
                if (!current.waitFor(timeoutSeconds + 30, TimeUnit.SECONDS)) {
                    return@withContext Result.Failure(
                        "timeout",
                        "The clearance window timed out. Try again or paste the cookie manually.",
                    )
                }
                val output = current.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parse(output)
            } catch (error: Exception) {
                Result.Failure(
                    "helper",
                    "The clearance helper failed: ${error.message ?: error::class.simpleName}",
                )
            } finally {
                if (current.isAlive) current.destroyForcibly()
                process = null
            }
        }
    }

    fun locate(): File? {
        val name = if (DesktopPlatform.os == DesktopPlatform.Os.WINDOWS) "$BINARY_NAME.exe" else BINARY_NAME
        val candidates = buildList {
            System.getenv("DAWNGASM_CLEARANCE_HELPER")?.let { add(File(it)) }
            System.getProperty("dawngasm.clearanceHelper")?.let { add(File(it)) }
            System.getProperty("compose.application.resources.dir")?.let { resources ->
                add(File(resources, "$HELPER_DIR/$name"))
                add(File(resources, name))
            }
            add(File(DesktopPlatform.configDir.toFile(), "native/$HELPER_DIR/$name"))
            add(File(DesktopPlatform.nativePlatformPath.toFile(), "$HELPER_DIR/$name"))

            val workingDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var cursor: File? = workingDir
            repeat(5) {
                val root = cursor ?: return@repeat
                add(File(root, "desktopApp/native/$HELPER_DIR/target/release/$name"))
                add(File(root, "native/$HELPER_DIR/target/release/$name"))
                add(File(root, "desktopApp/build/native-resources/$HELPER_DIR/$name"))
                add(File(root, "build/native-resources/$HELPER_DIR/$name"))
                cursor = root.parentFile
            }
        }
        return candidates
            .asSequence()
            .filter { it.isFile }
            .map { ensureExecutable(it) }
            .firstOrNull { it.canExecute() }
    }

    /**
     * Package tools occasionally lose the executable bit. Try to restore it in
     * place; if the install directory is read-only, copy the helper into the
     * per-user cache first.
     */
    private fun ensureExecutable(file: File): File {
        if (DesktopPlatform.os == DesktopPlatform.Os.WINDOWS || file.canExecute()) return file
        if (file.setExecutable(true, false) && file.canExecute()) return file

        return runCatching {
            val target = DesktopPlatform.cacheDir.resolve(HELPER_DIR).resolve(file.name)
            Files.createDirectories(target.parent)
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            val copied = target.toFile()
            copied.setExecutable(true, false)
            copied
        }.getOrDefault(file)
    }

    private fun parse(output: String): Result {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) {
            return Result.Failure("helper", "The clearance helper returned no response.")
        }
        val root = runCatching { Json.parseToJsonElement(trimmed).jsonObject }
            .getOrElse {
                return Result.Failure(
                    "protocol",
                    "The clearance helper returned an unexpected response.",
                )
            }

        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (ok) {
            val cookie = root["cookie"]?.jsonPrimitive?.content.orEmpty()
            val userAgent = root["user_agent"]?.jsonPrimitive?.content.orEmpty()
            val url = root["url"]?.jsonPrimitive?.content.orEmpty()
            return if (cookie.isBlank() || userAgent.isBlank()) {
                Result.Failure("protocol", "The clearance helper returned an incomplete response.")
            } else {
                Result.Success(Captured(cookie, userAgent, url))
            }
        }

        val error = root["error"]?.jsonObject
        return Result.Failure(
            error?.get("code")?.jsonPrimitive?.content ?: "helper",
            error?.get("message")?.jsonPrimitive?.content ?: "The clearance helper failed.",
        )
    }
}
