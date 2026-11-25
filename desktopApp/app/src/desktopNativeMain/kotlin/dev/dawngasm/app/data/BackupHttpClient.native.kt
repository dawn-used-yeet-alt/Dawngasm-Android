package dev.dawngasm.app.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

actual fun createCookieFreeHttpClient(): HttpClient =
    HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 45_000
        }
        followRedirects = true
    }
