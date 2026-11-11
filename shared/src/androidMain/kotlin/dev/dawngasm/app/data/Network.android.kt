package dev.dawngasm.app.data

import dev.dawngasm.app.model.TrackJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Cookie as OkCookie

actual object Network {
    actual val APP_USER_AGENT: String = "dawngasm-android/1.0"
    actual val BROWSER_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Dawngasm) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    val cookieStorage: CookiesStorage = AcceptAllCookiesStorage()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(MemoryCookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    actual val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                preconfigured = client
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
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            MemoryCookieJar.syncCookie(httpUrl, cookieHeader)
        }
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

/** Thread-safe in-memory cookie jar for OkHttp. */
object MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<OkCookie>()

    fun syncCookie(url: HttpUrl, rawCookieHeader: String) {
        if (rawCookieHeader.isBlank()) return
        val domain = url.host.removePrefix("api.")
        synchronized(cookies) {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt <= now }
            val parts = rawCookieHeader.split(';').map { it.trim() }
            for (part in parts) {
                if (part.isEmpty() || !part.contains('=')) continue
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=').trim()
                if (name.isEmpty()) continue
                try {
                    val cookie = OkCookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(domain)
                        .path("/")
                        .secure()
                        .build()
                    cookies.removeAll {
                        it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                    }
                    cookies.add(cookie)
                } catch (_: Exception) {}
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<OkCookie>) {
        synchronized(this.cookies) {
            val now = System.currentTimeMillis()
            this.cookies.removeAll { it.expiresAt <= now }
            for (cookie in cookies) {
                this.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this.cookies.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<OkCookie> {
        synchronized(cookies) {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt <= now }
            return cookies.filter { it.matches(url) }
        }
    }
}
