package dev.dawngasm.app.data

import dev.dawngasm.app.model.TrackJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

actual object Network {
    actual val APP_USER_AGENT: String = "dawngasm-desktop/1.0"

    actual val BROWSER_USER_AGENT: String
        get() {
            val (platform, chromeOs) = when (DesktopPlatform.os) {
                DesktopPlatform.Os.MACOS -> "Macintosh; Intel Mac OS X 10_15_7" to "Mac OS X 10_15_7"
                DesktopPlatform.Os.WINDOWS -> "Windows NT 10.0; Win64; x64" to "Windows NT 10.0; Win64; x64"
                DesktopPlatform.Os.LINUX -> "X11; Linux x86_64" to "Linux x86_64"
            }
            return "Mozilla/5.0 ($platform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 ($chromeOs)"
        }

    val cookieStorage: CookiesStorage = AcceptAllCookiesStorage()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    actual val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpCookies) {
                this.storage = cookieStorage
            }
            install(ContentNegotiation) {
                json(TrackJson.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 45_000
            }
            followRedirects = true
        }
    }

    actual fun syncCookie(url: String, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val parsedUrl = runCatching { Url(url) }.getOrNull() ?: return
        val domain = parsedUrl.host.removePrefix("api.")
        for (part in cookieHeader.split(';').map { it.trim() }) {
            if (part.isEmpty() || !part.contains('=')) continue
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=').trim()
            if (name.isNotEmpty()) {
                val cookie = Cookie(
                    name = name,
                    value = value,
                    domain = domain,
                    path = "/",
                    secure = true,
                )
                scope.launch {
                    cookieStorage.addCookie(parsedUrl, cookie)
                }
            }
        }
    }
}
