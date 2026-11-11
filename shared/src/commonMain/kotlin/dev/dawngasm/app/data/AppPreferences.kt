package dev.dawngasm.app.data

import com.russhwolf.settings.Settings
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.model.TrackJson

/**
 * Settings-backed store. Saved/history/downloads are full track
 * snapshots so the library still renders when the feed is unavailable.
 */
class AppPreferences(private val settings: Settings) {

    init {
        // One-time reset so existing installs pick up the new Top / Past week
        // home default instead of a previously persisted window.
        if (settings.getInt(KEY_DEFAULTS_VERSION, 0) < 1) {
            settings.remove(KEY_SORT)
            settings.remove(KEY_WINDOW)
            settings.putInt(KEY_DEFAULTS_VERSION, 1)
        }
    }

    fun sort(): String = settings.getStringOrNull(KEY_SORT)
        ?.takeIf { it in SearchSyntax.SORTS } ?: SearchSyntax.DEFAULT_SORT

    fun window(): String = settings.getStringOrNull(KEY_WINDOW)
        ?.takeIf { SearchSyntax.normalizeWindow(it) != null } ?: SearchSyntax.DEFAULT_WINDOW

    fun saveFeed(sort: String, window: String) {
        settings.putString(KEY_SORT, sort)
        settings.putString(KEY_WINDOW, window)
    }

    fun blockedTags(): List<String> = readTags(KEY_BLOCKED_TAGS)

    fun saveBlockedTags(tags: List<String>) {
        settings.putString(KEY_BLOCKED_TAGS, tags.joinToString("\u0001"))
    }

    fun downloadTreeUri(): String? = settings.getStringOrNull(KEY_DOWNLOAD_TREE)

    fun saveDownloadTreeUri(uri: String?) {
        if (uri == null) settings.remove(KEY_DOWNLOAD_TREE) else settings.putString(KEY_DOWNLOAD_TREE, uri)
    }

    /** The `cf_clearance` cookie and the User-Agent it was issued to. */
    fun hotaudioClearance(): Pair<String, String>? {
        val cookie = settings.getStringOrNull(KEY_HOTAUDIO_COOKIE).orEmpty()
        val userAgent = settings.getStringOrNull(KEY_HOTAUDIO_USER_AGENT).orEmpty()
        return (cookie to userAgent).takeIf { cookie.isNotBlank() }
    }

    fun saveHotaudioClearance(cookie: String, userAgent: String) {
        settings.putString(KEY_HOTAUDIO_COOKIE, cookie)
        settings.putString(KEY_HOTAUDIO_USER_AGENT, userAgent)
    }

    fun hotaudioIpFamily(): String? = settings.getStringOrNull(KEY_HOTAUDIO_IP_FAMILY)

    fun hotaudioClientIp(): String? = settings.getStringOrNull(KEY_HOTAUDIO_CLIENT_IP)

    fun saveHotaudioIpFamily(family: String?, ip: String? = null) {
        if (family == null) settings.remove(KEY_HOTAUDIO_IP_FAMILY) else settings.putString(KEY_HOTAUDIO_IP_FAMILY, family)
        if (ip != null) settings.putString(KEY_HOTAUDIO_CLIENT_IP, ip)
    }

    fun clearHotaudioClearance() {
        settings.remove(KEY_HOTAUDIO_COOKIE)
        settings.remove(KEY_HOTAUDIO_USER_AGENT)
        settings.remove(KEY_HOTAUDIO_IP_FAMILY)
        settings.remove(KEY_HOTAUDIO_CLIENT_IP)
    }

    /** The `__Secure-audiochan_playback` cookie and the User-Agent it was issued to. */
    fun audiochanClearance(): Pair<String, String>? {
        val cookie = settings.getStringOrNull(KEY_AUDIOCHAN_COOKIE).orEmpty()
        val userAgent = settings.getStringOrNull(KEY_AUDIOCHAN_USER_AGENT).orEmpty()
        return (cookie to userAgent).takeIf { cookie.isNotBlank() }
    }

    fun saveAudiochanClearance(cookie: String, userAgent: String) {
        settings.putString(KEY_AUDIOCHAN_COOKIE, cookie)
        settings.putString(KEY_AUDIOCHAN_USER_AGENT, userAgent)
    }

    fun clearAudiochanClearance() {
        settings.remove(KEY_AUDIOCHAN_COOKIE)
        settings.remove(KEY_AUDIOCHAN_USER_AGENT)
    }

    fun saved(): List<Track> = TrackJson.listFromJson(settings.getStringOrNull(KEY_SAVED))

    fun saveSaved(tracks: List<Track>) {
        settings.putString(KEY_SAVED, TrackJson.listToJson(tracks))
    }

    fun history(): List<Track> = TrackJson.listFromJson(settings.getStringOrNull(KEY_HISTORY))

    fun saveHistory(tracks: List<Track>) {
        settings.putString(KEY_HISTORY, TrackJson.listToJson(tracks))
    }

    fun downloads(): List<Track> = TrackJson.listFromJson(settings.getStringOrNull(KEY_DOWNLOADS))

    fun saveDownloads(tracks: List<Track>) {
        settings.putString(KEY_DOWNLOADS, TrackJson.listToJson(tracks))
    }

    private fun readTags(key: String): List<String> {
        val raw = settings.getStringOrNull(key) ?: return emptyList()
        return raw.split('\u0001').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    companion object {
        const val KEY_SORT = "feed_sort"
        const val KEY_WINDOW = "feed_window"
        const val KEY_DEFAULTS_VERSION = "defaults_version"
        const val KEY_BLOCKED_TAGS = "blocked_tags"
        const val KEY_DOWNLOAD_TREE = "download_tree"
        const val KEY_HOTAUDIO_COOKIE = "hotaudio_cookie"
        const val KEY_HOTAUDIO_USER_AGENT = "hotaudio_user_agent"
        const val KEY_HOTAUDIO_IP_FAMILY = "hotaudio_ip_family"
        const val KEY_HOTAUDIO_CLIENT_IP = "hotaudio_client_ip"
        const val KEY_AUDIOCHAN_COOKIE = "audiochan_cookie"
        const val KEY_AUDIOCHAN_USER_AGENT = "audiochan_user_agent"
        const val KEY_SAVED = "saved_tracks"
        const val KEY_HISTORY = "history_tracks"
        const val KEY_DOWNLOADS = "downloaded_tracks"
    }
}
