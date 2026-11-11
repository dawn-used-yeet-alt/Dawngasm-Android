package dev.dawngasm.app.data

import dev.dawngasm.app.model.TrackJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
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
    actual val APP_USER_AGENT: String = "dawngasm-ios/1.0"
    actual val BROWSER_USER_AGENT: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    val cookieStorage: CookiesStorage = AcceptAllCookiesStorage()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    actual val httpClient: HttpClient by lazy {
        HttpClient(Darwin) {
            engine {
                configureRequest {
                    setAllowsCellularAccess(true)
                }
            }
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
        val parts = cookieHeader.split(';').map { it.trim() }
        for (part in parts) {
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
