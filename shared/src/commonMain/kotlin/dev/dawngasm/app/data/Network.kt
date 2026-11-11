package dev.dawngasm.app.data

import io.ktor.client.HttpClient

expect object Network {
    val APP_USER_AGENT: String
    val BROWSER_USER_AGENT: String
    val httpClient: HttpClient
    fun syncCookie(url: String, cookieHeader: String)
}
