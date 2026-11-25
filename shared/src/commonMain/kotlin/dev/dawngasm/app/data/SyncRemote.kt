package dev.dawngasm.app.data

import dev.dawngasm.app.crypto.BackupCrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SyncConflictException(message: String) : Exception(message)

data class RemotePullResult(
    val payload: String?,
    val notModified: Boolean,
    val etag: String?,
)

data class RemotePushResult(
    val etag: String?,
)

/**
 * Pluggable remote backend for continuous encrypted sync.
 */
interface SyncRemote {
    val isReadOnly: Boolean
    val supportsArchiveChunks: Boolean get() = false

    suspend fun pull(ifNoneMatch: String? = null): RemotePullResult
    suspend fun push(payload: String, expectedEtag: String? = null): RemotePushResult

    suspend fun pullChunk(chunkId: String): String? = null
    suspend fun pushChunk(chunkId: String, payload: String): Unit = Unit
}

/**
 * Generic HTTP `GET` / `PUT` backend for self-hosted servers or WebDAV.
 */
class HttpSyncRemote(
    private val url: String,
    private val authorizationHeader: String? = null,
    private val client: HttpClient = BackupHttpClient.client,
) : SyncRemote {
    override val isReadOnly: Boolean = false

    override suspend fun pull(ifNoneMatch: String?): RemotePullResult {
        val trimmed = url.trim()
        require(trimmed.startsWith("https://", ignoreCase = true) || isLocalHttp(trimmed)) {
            "Sync URL must use HTTPS (except localhost / 127.0.0.1)"
        }
        val response = client.get(trimmed) {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "text/plain, application/octet-stream, */*")
            if (!authorizationHeader.isNullOrBlank()) {
                header("Authorization", authorizationHeader.trim())
            }
            if (!ifNoneMatch.isNullOrBlank()) {
                header("If-None-Match", ifNoneMatch)
            }
        }
        return when (response.status) {
            HttpStatusCode.NotModified -> RemotePullResult(null, notModified = true, etag = ifNoneMatch)
            HttpStatusCode.NotFound -> RemotePullResult(null, notModified = false, etag = null)
            else -> {
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Sync GET returned HTTP ${response.status.value}")
                }
                val text = response.bodyAsText().trim()
                RemotePullResult(
                    payload = text.takeIf { it.isNotEmpty() },
                    notModified = false,
                    etag = response.headers["ETag"],
                )
            }
        }
    }

    override suspend fun push(payload: String, expectedEtag: String?): RemotePushResult {
        val trimmed = url.trim()
        require(trimmed.startsWith("https://", ignoreCase = true) || isLocalHttp(trimmed)) {
            "Sync URL must use HTTPS (except localhost / 127.0.0.1)"
        }
        val response = client.put(trimmed) {
            header("User-Agent", Network.APP_USER_AGENT)
            contentType(ContentType.Text.Plain)
            if (!authorizationHeader.isNullOrBlank()) {
                header("Authorization", authorizationHeader.trim())
            }
            if (!expectedEtag.isNullOrBlank()) {
                header("If-Match", expectedEtag)
            }
            setBody(payload)
        }
        if (response.status == HttpStatusCode.PreconditionFailed || response.status == HttpStatusCode.Conflict) {
            throw SyncConflictException("Remote changed concurrently (HTTP ${response.status.value})")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Sync PUT returned HTTP ${response.status.value}")
        }
        return RemotePushResult(etag = response.headers["ETag"])
    }

    companion object {
        internal fun isLocalHttp(url: String): Boolean {
            val lower = url.lowercase()
            return lower.startsWith("http://localhost") ||
                lower.startsWith("http://127.0.0.1") ||
                lower.startsWith("http://[::1]")
        }
    }
}

/**
 * GitHub Gist sync backend using `GET /gists/{id}` and `PATCH /gists/{id}`
 * with a `gist`-scoped Personal Access Token. Supports multi-file history archive chunks.
 */
class GitHubGistSyncRemote(
    gistIdOrUrl: String,
    private val pat: String,
    private val filename: String = SyncConfig.DEFAULT_GIST_FILENAME,
    private val client: HttpClient = BackupHttpClient.client,
) : SyncRemote {
    private val gistId: String = BackupUrlResolver.extractGistId(gistIdOrUrl)
        ?: throw IllegalArgumentException("Invalid GitHub Gist ID or URL")

    override val isReadOnly: Boolean = pat.isBlank()
    override val supportsArchiveChunks: Boolean = true

    override suspend fun pull(ifNoneMatch: String?): RemotePullResult {
        val response = client.get("$GIST_API_BASE/$gistId") {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            if (pat.isNotBlank()) {
                header("Authorization", "Bearer ${pat.trim()}")
            }
            if (!ifNoneMatch.isNullOrBlank()) {
                header("If-None-Match", ifNoneMatch)
            }
        }
        if (response.status == HttpStatusCode.NotModified) {
            return RemotePullResult(null, notModified = true, etag = ifNoneMatch)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GitHub Gist pull failed (HTTP ${response.status.value})")
        }
        val etag = response.headers["ETag"]
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val files = root["files"]?.jsonObject ?: return RemotePullResult(null, notModified = false, etag = etag)
        val fileObj = files[filename]?.jsonObject
            ?: findEncryptedGistFile(files)
            ?: return RemotePullResult(null, notModified = false, etag = etag)

        val text = readGistFileContent(fileObj)?.trim()
        return RemotePullResult(
            payload = text?.takeIf { it.isNotEmpty() },
            notModified = false,
            etag = etag,
        )
    }

    private fun findEncryptedGistFile(files: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject? {
        for ((_, el) in files.entries) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            if (BackupCrypto.isEncryptedBackup(content)) return obj
        }
        return null
    }

    override suspend fun push(payload: String, expectedEtag: String?): RemotePushResult {
        if (pat.isBlank()) {
            throw UnsupportedOperationException("GitHub Gist PAT is required to push updates")
        }
        val bodyJson = buildJsonObject {
            put("description", "Dawngasm Encrypted Sync")
            put(
                "files",
                buildJsonObject {
                    put(
                        filename,
                        buildJsonObject {
                            put("content", payload)
                        },
                    )
                },
            )
        }.toString()

        val response = client.patch("$GIST_API_BASE/$gistId") {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            header("Authorization", "Bearer ${pat.trim()}")
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GitHub Gist push failed (HTTP ${response.status.value})")
        }
        return RemotePushResult(etag = response.headers["ETag"])
    }

    override suspend fun pullChunk(chunkId: String): String? {
        val response = client.get("$GIST_API_BASE/$gistId") {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            if (pat.isNotBlank()) {
                header("Authorization", "Bearer ${pat.trim()}")
            }
        }
        if (!response.status.isSuccess()) return null
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val files = root["files"]?.jsonObject ?: return null
        val chunkName = chunkFilename(chunkId)
        val fileObj = files[chunkName]?.jsonObject ?: return null
        return readGistFileContent(fileObj)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun pushChunk(chunkId: String, payload: String) {
        if (pat.isBlank()) return
        val chunkName = chunkFilename(chunkId)
        val bodyJson = buildJsonObject {
            put(
                "files",
                buildJsonObject {
                    put(
                        chunkName,
                        buildJsonObject {
                            put("content", payload)
                        },
                    )
                },
            )
        }.toString()
        val response = client.patch("$GIST_API_BASE/$gistId") {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            header("Authorization", "Bearer ${pat.trim()}")
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GitHub Gist chunk push failed (HTTP ${response.status.value})")
        }
    }

    private suspend fun readGistFileContent(fileObj: kotlinx.serialization.json.JsonObject): String? {
        val truncated = fileObj["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val content = fileObj["content"]?.jsonPrimitive?.content
        if (!truncated && !content.isNullOrBlank()) return content
        val rawUrl = fileObj["raw_url"]?.jsonPrimitive?.content ?: return content
        val resp = client.get(rawUrl) {
            header("User-Agent", Network.APP_USER_AGENT)
        }
        return if (resp.status.isSuccess()) resp.bodyAsText() else content
    }

    private fun chunkFilename(chunkId: String): String =
        "dawngasm-history-$chunkId.txt"

    data class CreatedGist(
        val gistId: String,
        val htmlUrl: String,
        val etag: String?,
    )

    companion object {
        private const val GIST_API_BASE = "https://api.github.com/gists"
        private const val GITHUB_API_VERSION = "2022-11-28"

        suspend fun createPrivateGist(
            pat: String,
            filename: String = SyncConfig.DEFAULT_GIST_FILENAME,
            initialContent: String,
            client: HttpClient = BackupHttpClient.client,
        ): CreatedGist {
            require(pat.isNotBlank()) { "GitHub PAT is required to create a private gist" }
            require(initialContent.isNotBlank()) { "Initial gist content cannot be empty" }
            val safeFilename = filename.ifBlank { SyncConfig.DEFAULT_GIST_FILENAME }
            val bodyJson = buildJsonObject {
                put("description", "Dawngasm Encrypted Sync")
                put("public", false)
                put(
                    "files",
                    buildJsonObject {
                        put(
                            safeFilename,
                            buildJsonObject {
                                put("content", initialContent)
                            },
                        )
                    },
                )
            }.toString()

            val response = client.post(GIST_API_BASE) {
                header("User-Agent", Network.APP_USER_AGENT)
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                header("Authorization", "Bearer ${pat.trim()}")
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Could not create private GitHub Gist (HTTP ${response.status.value})")
            }
            val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val id = root["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("GitHub Gist creation returned no ID")
            val htmlUrl = root["html_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: "https://gist.github.com/$id"
            return CreatedGist(
                gistId = id,
                htmlUrl = htmlUrl,
                etag = response.headers["ETag"],
            )
        }
    }
}

/**
 * Read-only HTTPS link + password sync backend. Pulls and merges updates
 * without pushing back.
 */
class ReadOnlyLinkSyncRemote(
    private val url: String,
    private val client: HttpClient = BackupHttpClient.client,
) : SyncRemote {
    override val isReadOnly: Boolean = true

    override suspend fun pull(ifNoneMatch: String?): RemotePullResult =
        BackupUrlResolver.fetchText(
            url = url,
            client = client,
            ifNoneMatch = ifNoneMatch,
            requireHttps = true,
        )

    override suspend fun push(payload: String, expectedEtag: String?): RemotePushResult {
        throw UnsupportedOperationException("Read-only sync link cannot push changes")
    }
}
