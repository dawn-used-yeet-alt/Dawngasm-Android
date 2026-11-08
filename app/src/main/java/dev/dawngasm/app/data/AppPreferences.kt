package dev.dawngasm.app.data

import android.content.Context
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.Track

/**
 * Small SharedPreferences-backed store. Saved/history/downloads are full track
 * snapshots so the library still renders when the feed is unavailable.
 */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("dawngasm", Context.MODE_PRIVATE)

    init {
        // One-time reset so existing installs pick up the new Top / Past week
        // home default instead of a previously persisted window.
        if (prefs.getInt(KEY_DEFAULTS_VERSION, 0) < 1) {
            prefs.edit()
                .remove(KEY_SORT)
                .remove(KEY_WINDOW)
                .putInt(KEY_DEFAULTS_VERSION, 1)
                .apply()
        }
    }

    fun sort(): String = prefs.getString(KEY_SORT, SearchSyntax.DEFAULT_SORT)
        ?.takeIf { it in SearchSyntax.SORTS } ?: SearchSyntax.DEFAULT_SORT

    fun window(): String = prefs.getString(KEY_WINDOW, SearchSyntax.DEFAULT_WINDOW)
        ?.takeIf { SearchSyntax.normalizeWindow(it) != null } ?: SearchSyntax.DEFAULT_WINDOW

    fun saveFeed(sort: String, window: String) {
        prefs.edit().putString(KEY_SORT, sort).putString(KEY_WINDOW, window).apply()
    }

    fun blockedTags(): List<String> = readTags(KEY_BLOCKED_TAGS)

    fun saveBlockedTags(tags: List<String>) {
        prefs.edit().putString(KEY_BLOCKED_TAGS, tags.joinToString("\u0001")).apply()
    }

    fun downloadTreeUri(): String? = prefs.getString(KEY_DOWNLOAD_TREE, null)

    fun saveDownloadTreeUri(uri: String?) {
        prefs.edit().apply {
            if (uri == null) remove(KEY_DOWNLOAD_TREE) else putString(KEY_DOWNLOAD_TREE, uri)
        }.apply()
    }

    /** The `cf_clearance` cookie and the User-Agent it was issued to. */
    fun hotaudioClearance(): Pair<String, String>? {
        val cookie = prefs.getString(KEY_HOTAUDIO_COOKIE, null).orEmpty()
        val userAgent = prefs.getString(KEY_HOTAUDIO_USER_AGENT, null).orEmpty()
        return (cookie to userAgent).takeIf { cookie.isNotBlank() }
    }

    fun saveHotaudioClearance(cookie: String, userAgent: String) {
        prefs.edit()
            .putString(KEY_HOTAUDIO_COOKIE, cookie)
            .putString(KEY_HOTAUDIO_USER_AGENT, userAgent)
            .apply()
    }

    fun hotaudioIpFamily(): String? = prefs.getString(KEY_HOTAUDIO_IP_FAMILY, null)

    fun hotaudioClientIp(): String? = prefs.getString(KEY_HOTAUDIO_CLIENT_IP, null)

    fun saveHotaudioIpFamily(family: String?, ip: String? = null) {
        prefs.edit().apply {
            if (family == null) remove(KEY_HOTAUDIO_IP_FAMILY) else putString(KEY_HOTAUDIO_IP_FAMILY, family)
            if (ip != null) putString(KEY_HOTAUDIO_CLIENT_IP, ip)
        }.apply()
    }

    fun clearHotaudioClearance() {
        prefs.edit()
            .remove(KEY_HOTAUDIO_COOKIE)
            .remove(KEY_HOTAUDIO_USER_AGENT)
            .remove(KEY_HOTAUDIO_IP_FAMILY)
            .remove(KEY_HOTAUDIO_CLIENT_IP)
            .apply()
    }

    fun saved(): List<Track> = Track.listFromJson(prefs.getString(KEY_SAVED, null))

    fun saveSaved(tracks: List<Track>) {
        prefs.edit().putString(KEY_SAVED, Track.listToJson(tracks)).apply()
    }

    fun history(): List<Track> = Track.listFromJson(prefs.getString(KEY_HISTORY, null))

    fun saveHistory(tracks: List<Track>) {
        prefs.edit().putString(KEY_HISTORY, Track.listToJson(tracks)).apply()
    }

    fun downloads(): List<Track> = Track.listFromJson(prefs.getString(KEY_DOWNLOADS, null))

    fun saveDownloads(tracks: List<Track>) {
        prefs.edit().putString(KEY_DOWNLOADS, Track.listToJson(tracks)).apply()
    }

    private fun readTags(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return raw.split('\u0001').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private companion object {
        const val KEY_SORT = "feed_sort"
        const val KEY_WINDOW = "feed_window"
        const val KEY_DEFAULTS_VERSION = "defaults_version"
        const val KEY_BLOCKED_TAGS = "blocked_tags"
        const val KEY_DOWNLOAD_TREE = "download_tree"
        const val KEY_HOTAUDIO_COOKIE = "hotaudio_cookie"
        const val KEY_HOTAUDIO_USER_AGENT = "hotaudio_user_agent"
        const val KEY_HOTAUDIO_IP_FAMILY = "hotaudio_ip_family"
        const val KEY_HOTAUDIO_CLIENT_IP = "hotaudio_client_ip"
        const val KEY_SAVED = "saved_tracks"
        const val KEY_HISTORY = "history_tracks"
        const val KEY_DOWNLOADS = "downloaded_tracks"
    }
}
