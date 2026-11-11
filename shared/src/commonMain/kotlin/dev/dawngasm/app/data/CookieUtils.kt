package dev.dawngasm.app.data

/**
 * Helpers for parsing, merging, and inspecting HTTP Cookie header strings.
 */
object CookieUtils {

    /**
     * Merges multiple Cookie header strings, deduplicating individual `name=value` pairs.
     * When duplicate keys exist, the last occurrence takes precedence.
     */
    fun mergeCookies(vararg cookieHeaders: String?): String {
        val cookieMap = linkedMapOf<String, String>()
        for (header in cookieHeaders) {
            if (header.isNullOrBlank()) continue
            val pairs = header.split(';')
            for (pair in pairs) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty() || !trimmed.contains('=')) continue
                val name = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                if (name.isNotEmpty()) {
                    cookieMap[name] = value
                }
            }
        }
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Checks whether a raw cookie header contains a specific cookie name.
     */
    fun hasCookie(cookieHeader: String?, cookieName: String): Boolean {
        if (cookieHeader.isNullOrBlank() || cookieName.isBlank()) return false
        val prefix = "$cookieName="
        return cookieHeader.split(';').any {
            val trimmed = it.trim()
            trimmed.startsWith(prefix, ignoreCase = true) && trimmed.length > prefix.length
        }
    }

    /**
     * Extracts the value of a specific cookie from a cookie header string.
     */
    fun extractCookieValue(cookieHeader: String?, cookieName: String): String? {
        if (cookieHeader.isNullOrBlank() || cookieName.isBlank()) return null
        val prefix = "$cookieName="
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (trimmed.startsWith(prefix, ignoreCase = true)) {
                return trimmed.substring(prefix.length).trim()
            }
        }
        return null
    }
}
