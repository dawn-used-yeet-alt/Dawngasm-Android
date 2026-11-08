package dev.dawngasm.app.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * One client for the index, the extractors, the media data source and
 * downloads. The shared cookie jar is what makes audiochan's play-intent flow
 * work: the cookie set by the API call rides the ranged stream requests.
 */
object Network {
    const val APP_USER_AGENT = "dawngasm-android/1.0"
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Dawngasm) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

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

    fun syncCookie(url: String, cookieHeader: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        MemoryCookieJar.syncCookie(httpUrl, cookieHeader)
    }
}

/** Thread-safe in-memory cookie jar; nothing leaves the app process. */
object MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

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
                    val cookie = Cookie.Builder()
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

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
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

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(cookies) {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt <= now }
            return cookies.filter { it.matches(url) }
        }
    }
}

class HttpException(message: String) : IOException(message)
