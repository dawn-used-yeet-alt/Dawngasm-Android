@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dawngasm.desktop

import dev.dawngasm.app.data.DesktopPlatform
import dev.dawngasm.app.util.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.system

object NativeClearanceHelper {
    private val BINARY_NAME = if (DesktopPlatform.os == DesktopPlatform.Os.WINDOWS) {
        "dawngasm-clearance-helper.exe"
    } else {
        "dawngasm-clearance-helper"
    }

    data class Captured(val cookie: String, val userAgent: String, val url: String)

    sealed interface Result {
        data class Success(val captured: Captured) : Result
        data class Failure(val code: String, val message: String) : Result
    }

    class Session {
        private var cancelled = false

        fun cancel() {
            cancelled = true
        }

        suspend fun capture(
            url: String,
            cookieUrls: List<String>,
            requiredCookie: String,
            title: String,
            initScript: String? = null,
            timeoutSeconds: Long = 300,
        ): Result = withContext(Dispatchers.Default) {
            val executable = locate() ?: return@withContext Result.Failure(
                "missing",
                "The native clearance helper was not found. Please paste the cookie header manually.",
            )

            val requestJson = buildJsonObject {
                put("url", url)
                put("cookie_urls", buildJsonArray { cookieUrls.forEach { add(it) } })
                put("required_cookie", requiredCookie)
                put("title", title)
                put("timeout_seconds", timeoutSeconds)
                if (initScript != null) put("init_script", initScript)
            }.toString()

            val timestamp = currentTimeMillis()
            val reqPath = "${DesktopPlatform.cacheDir}/clearance_req_$timestamp.json".toPath()
            val respPath = "${DesktopPlatform.cacheDir}/clearance_resp_$timestamp.json".toPath()

            try {
                reqPath.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
                FileSystem.SYSTEM.write(reqPath) {
                    writeUtf8(requestJson)
                }

                if (cancelled) return@withContext Result.Failure("cancelled", "Clearance was cancelled.")

                val command = "\"$executable\" < \"$reqPath\" > \"$respPath\""
                system(command)

                if (cancelled) return@withContext Result.Failure("cancelled", "Clearance was cancelled.")

                val output = if (FileSystem.SYSTEM.exists(respPath)) {
                    FileSystem.SYSTEM.read(respPath) { readUtf8() }
                } else null

                if (output.isNullOrBlank()) {
                    Result.Failure("helper", "The clearance helper closed without returning a response.")
                } else {
                    parse(output)
                }
            } catch (t: Throwable) {
                Result.Failure("error", t.message ?: "Failed to execute clearance helper.")
            } finally {
                runCatching { FileSystem.SYSTEM.delete(reqPath) }
                runCatching { FileSystem.SYSTEM.delete(respPath) }
            }
        }
    }

    fun locate(): String? {
        val env = DesktopPlatform.env("DAWNGASM_CLEARANCE_HELPER")
        if (!env.isNullOrBlank() && FileSystem.SYSTEM.exists(env.toPath())) return env

        val appDir = DesktopPlatform.env("APPDIR")
        if (!appDir.isNullOrBlank()) {
            val inAppDirUsr = "$appDir/usr/bin/$BINARY_NAME"
            if (FileSystem.SYSTEM.exists(inAppDirUsr.toPath())) return inAppDirUsr
            val inAppDirBin = "$appDir/bin/$BINARY_NAME"
            if (FileSystem.SYSTEM.exists(inAppDirBin.toPath())) return inAppDirBin
        }

        val owd = DesktopPlatform.env("OWD")
        if (!owd.isNullOrBlank()) {
            val inOwd = "$owd/$BINARY_NAME"
            if (FileSystem.SYSTEM.exists(inOwd.toPath())) return inOwd
        }

        val candidates = listOf(
            "${DesktopPlatform.configDir}/clearance-helper/$BINARY_NAME",
            "${DesktopPlatform.cacheDir}/clearance-helper/$BINARY_NAME",
            "./$BINARY_NAME",
            "./native/clearance-helper/target/release/$BINARY_NAME",
            "../native/clearance-helper/target/release/$BINARY_NAME",
            "/usr/local/bin/$BINARY_NAME",
            "/usr/bin/$BINARY_NAME",
        )
        for (candidate in candidates) {
            if (FileSystem.SYSTEM.exists(candidate.toPath())) return candidate
        }

        val devNull = if (DesktopPlatform.os == DesktopPlatform.Os.WINDOWS) ">nul 2>&1" else ">/dev/null 2>&1"
        if (system("$BINARY_NAME --describe $devNull") == 0) {
            return BINARY_NAME
        }

        return null
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
                    "Invalid clearance helper response: ${trimmed.take(160)}",
                )
            }

        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            val cookie = root["cookie"]?.jsonPrimitive?.content.orEmpty()
            val userAgent = root["user_agent"]?.jsonPrimitive?.content.orEmpty()
            val url = root["url"]?.jsonPrimitive?.content.orEmpty()
            if (cookie.isEmpty()) {
                return Result.Failure("protocol", "Clearance helper reported success without a cookie.")
            }
            return Result.Success(
                Captured(
                    cookie = cookie,
                    userAgent = userAgent,
                    url = url,
                ),
            )
        }

        val errObj = root["error"]?.jsonObject
        val code = errObj?.get("code")?.jsonPrimitive?.content ?: "unknown"
        val message = errObj?.get("message")?.jsonPrimitive?.content
            ?: "The clearance flow did not succeed."
        return Result.Failure(code, message)
    }
}
