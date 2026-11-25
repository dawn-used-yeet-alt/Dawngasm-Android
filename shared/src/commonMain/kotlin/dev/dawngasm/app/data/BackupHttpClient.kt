package dev.dawngasm.app.data

import io.ktor.client.HttpClient

/**
 * Cookie-free Ktor [HttpClient] used exclusively for encrypted backup imports
 * and remote sync so host clearance cookies (`cf_clearance`, audiochan session
 * cookies) are never attached or leaked to third-party endpoints.
 */
expect fun createCookieFreeHttpClient(): HttpClient

object BackupHttpClient {
    val client: HttpClient by lazy { createCookieFreeHttpClient() }
}
