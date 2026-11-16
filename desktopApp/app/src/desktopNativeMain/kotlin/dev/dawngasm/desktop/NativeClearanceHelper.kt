package dev.dawngasm.desktop

import dev.dawngasm.app.data.DesktopPlatform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
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
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.pclose
import platform.posix.popen
import platform.posix.readlink
import platform.posix.unlink

object NativeClearanceHelper {
    private const val BINARY_NAME = "dawngasm-clearance-helper"

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

            val tempFile = "${DesktopPlatform.cacheDir}/clearance_req_${getpid()}.json"
            try {
                @OptIn(ExperimentalForeignApi::class)
                val writeOk = run {
                    val fp = fopen(tempFile, "w") ?: return@run false
                    fputs(requestJson, fp)
                    fclose(fp)
                    true
                }
                if (!writeOk) {
                    return@withContext Result.Failure("io", "Could not write clearance request file.")
                }

                if (cancelled) return@withContext Result.Failure("cancelled", "Clearance was cancelled.")

                val command = "'$executable' < '$tempFile'"
                val output = executeCommand(command)

                if (cancelled) return@withContext Result.Failure("cancelled", "Clearance was cancelled.")

                if (output.isNullOrBlank()) {
                    Result.Failure("helper", "The clearance helper closed without returning a response.")
                } else {
                    parse(output)
                }
            } finally {
                unlink(tempFile)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun locate(): String? {
        val env = getenv("DAWNGASM_CLEARANCE_HELPER")?.toKString()
        if (!env.isNullOrBlank() && access(env, X_OK) == 0) return env

        val exeDir = getExecutableDir()
        if (exeDir != null) {
            val candidate = "$exeDir/$BINARY_NAME"
            if (access(candidate, X_OK) == 0) return candidate
            val candidateExe = "$exeDir/$BINARY_NAME.exe"
            if (access(candidateExe, X_OK) == 0) return candidateExe
        }

        val configDirCandidate = "${DesktopPlatform.configDir}/clearance-helper/$BINARY_NAME"
        if (access(configDirCandidate, X_OK) == 0) return configDirCandidate

        val cacheDirCandidate = "${DesktopPlatform.cacheDir}/clearance-helper/$BINARY_NAME"
        if (access(cacheDirCandidate, X_OK) == 0) return cacheDirCandidate

        val standardPaths = listOf(
            "/usr/local/bin/$BINARY_NAME",
            "/usr/bin/$BINARY_NAME",
        )
        for (p in standardPaths) {
            if (access(p, X_OK) == 0) return p
        }

        return null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getExecutableDir(): String? = memScoped {
        val buf = allocArray<platform.posix.charVar>(1024)
        val len = readlink("/proc/self/exe", buf, 1024)
        if (len > 0) {
            val fullPath = buf.toKString()
            val lastSlash = fullPath.lastIndexOf('/')
            if (lastSlash > 0) fullPath.substring(0, lastSlash) else null
        } else null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun executeCommand(command: String): String? = memScoped {
        val fp = popen(command, "r") ?: return null
        val buffer = allocArray<platform.posix.charVar>(4096)
        val output = StringBuilder()
        try {
            while (fgets(buffer, 4096, fp) != null) {
                output.append(buffer.toKString())
            }
        } finally {
            pclose(fp)
        }
        output.toString()
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
                    "The clearance helper returned an unexpected response: $trimmed",
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
