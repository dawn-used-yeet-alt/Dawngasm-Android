package dev.dawngasm.app.data

import dev.dawngasm.app.crypto.BackupCrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves HTTPS backup/sync URLs (including GitHub Gist pages and GitHub `blob`
 * links) into raw text payloads using the cookie-free [HttpClient].
 */
object BackupUrlResolver {
    private val githubBlobRegex = Regex(
        """^https://github\.com/([^/]+)/([^/]+)/blob/(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val gistPageRegex = Regex(
        """^https://gist\.github\.com/(?:[^/]+/)?([0-9a-fA-F]+)(?:/raw(?:/.*)?)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val gistRawRegex = Regex(
        """^https://gist\.githubusercontent\.com/([^/]+)/([0-9a-fA-F]+)/raw(?:/.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun extractGistId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.matches(Regex("""^[0-9a-fA-F]{16,64}$"""))) return trimmed
        gistPageRegex.matchEntire(trimmed)?.let { return it.groupValues[1] }
        gistRawRegex.matchEntire(trimmed)?.let { return it.groupValues[2] }
        return null
    }

    /**
     * Converts GitHub `blob` URLs to `raw.githubusercontent.com` and preserves
     * direct HTTPS URLs. Gist page URLs without `/raw` are handled via the
     * GitHub Gists API when fetching so the right file is selected cleanly.
     */
    fun normalizeDirectUrl(url: String): String {
        val trimmed = url.trim()
        githubBlobRegex.matchEntire(trimmed)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            val rest = match.groupValues[3]
            return "https://raw.githubusercontent.com/$owner/$repo/$rest"
        }
        return trimmed
    }

    fun isAllowedImportUrl(url: String): Boolean =
        url.trim().startsWith("https://", ignoreCase = true)

    suspend fun fetchText(
        url: String,
        client: HttpClient = BackupHttpClient.client,
        ifNoneMatch: String? = null,
        requireHttps: Boolean = true,
    ): RemotePullResult {
        val trimmed = url.trim()
        if (requireHttps && !isAllowedImportUrl(trimmed)) {
            throw IllegalArgumentException("Backup link must use HTTPS")
        }

        // If this is a Gist page URL (not already a /raw URL), resolve via Gists API
        val isRawGist = trimmed.contains("gist.githubusercontent.com", ignoreCase = true) ||
            trimmed.contains("/raw", ignoreCase = true)
        val gistId = if (!isRawGist) extractGistId(trimmed) else null
        if (gistId != null) {
            return fetchGistPublic(gistId, client, ifNoneMatch)
        }

        val resolvedUrl = normalizeDirectUrl(trimmed)
        val response = client.get(resolvedUrl) {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "text/plain, application/octet-stream, */*")
            if (!ifNoneMatch.isNullOrBlank()) {
                header("If-None-Match", ifNoneMatch)
            }
        }
        if (response.status == HttpStatusCode.NotModified) {
            return RemotePullResult(payload = null, notModified = true, etag = ifNoneMatch)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to fetch backup link (HTTP ${response.status.value})")
        }
        return RemotePullResult(
            payload = response.bodyAsText().trim(),
            notModified = false,
            etag = response.headers["ETag"],
        )
    }

    private suspend fun fetchGistPublic(
        gistId: String,
        client: HttpClient,
        ifNoneMatch: String?,
    ): RemotePullResult {
        val response = client.get("https://api.github.com/gists/$gistId") {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            if (!ifNoneMatch.isNullOrBlank()) {
                header("If-None-Match", ifNoneMatch)
            }
        }
        if (response.status == HttpStatusCode.NotModified) {
            return RemotePullResult(payload = null, notModified = true, etag = ifNoneMatch)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GitHub Gist returned HTTP ${response.status.value}")
        }
        val etag = response.headers["ETag"]
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val files = root["files"]?.jsonObject
            ?: throw IllegalStateException("GitHub Gist contains no files")

        val chosen = selectGistBackupFile(files)
            ?: throw IllegalStateException("GitHub Gist contains no readable text file")

        val truncated = chosen["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val rawUrl = chosen["raw_url"]?.jsonPrimitive?.content
        val content = if (!truncated) {
            chosen["content"]?.jsonPrimitive?.content
        } else {
            null
        }
        val text = if (!content.isNullOrBlank()) {
            content
        } else if (!rawUrl.isNullOrBlank()) {
            val rawResp = client.get(rawUrl) {
                header("User-Agent", Network.APP_USER_AGENT)
            }
            if (!rawResp.status.isSuccess()) {
                throw IllegalStateException("Failed to fetch Gist raw file (HTTP ${rawResp.status.value})")
            }
            rawResp.bodyAsText()
        } else {
            throw IllegalStateException("GitHub Gist file has no content")
        }

        return RemotePullResult(payload = text.trim(), notModified = false, etag = etag)
    }

    internal fun selectGistBackupFile(files: JsonObject): JsonObject? {
        val candidates = files.entries.mapNotNull { (name, el) ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            name to obj
        }
        // Prefer standard filename, then any encrypted backup content, then first file
        candidates.firstOrNull { it.first.equals(SyncConfig.DEFAULT_GIST_FILENAME, ignoreCase = true) }
            ?.let { return it.second }
        candidates.firstOrNull { (_, obj) ->
            val c = obj["content"]?.jsonPrimitive?.content.orEmpty()
            BackupCrypto.isEncryptedBackup(c)
        }?.let { return it.second }
        candidates.firstOrNull { (name, _) ->
            name.contains("dawngasm", ignoreCase = true) && !name.contains("history-", ignoreCase = true)
        }?.let { return it.second }
        return candidates.firstOrNull()?.second
    }
}
