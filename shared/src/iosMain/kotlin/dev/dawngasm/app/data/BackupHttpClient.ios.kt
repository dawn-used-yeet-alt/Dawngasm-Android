package dev.dawngasm.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

actual fun createCookieFreeHttpClient(): HttpClient =
    HttpClient(Darwin) {
        engine {
            configureSession {
                HTTPCookieStorage = null
                HTTPShouldSetCookies = false
            }
            configureRequest {
                setAllowsCellularAccess(true)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 45_000
        }
        followRedirects = true
    }
