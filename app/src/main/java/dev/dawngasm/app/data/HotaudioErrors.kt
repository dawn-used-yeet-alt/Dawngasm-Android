package dev.dawngasm.app.data

import net.hotaudio.extractor.FfiException

/** Shared classification and human-readable messages for hotaudio failures. */
object HotaudioErrors {
    fun detail(error: Throwable): String = when (error) {
        is FfiException.Failed -> error.detail
        is FfiException.Panic -> error.detail
        else -> error.message.orEmpty()
    }

    fun isChallenge(error: Throwable): Boolean =
        (error is FfiException.Failed && error.code.equals("challenge", ignoreCase = true)) ||
            detail(error).contains("challenge", ignoreCase = true)

    fun isRateLimit(error: Throwable): Boolean {
        val text = "${error.message.orEmpty()} ${detail(error)}"
        return text.contains("429") || text.contains("too many requests", ignoreCase = true)
    }

    fun isTimeout(error: Throwable): Boolean {
        val text = "${error.message.orEmpty()} ${detail(error)}"
        return text.contains("timeout", ignoreCase = true) ||
            text.contains("timed out", ignoreCase = true)
    }

    fun isDnsOrTransient(error: Throwable): Boolean {
        val text = "${error.message.orEmpty()} ${detail(error)}"
        return text.contains("failed to lookup address information", ignoreCase = true) ||
            text.contains("No address associated with hostname", ignoreCase = true) ||
            text.contains("Name or service not known", ignoreCase = true) ||
            text.contains("connection refused", ignoreCase = true) ||
            text.contains("connection reset", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true) ||
            text.contains("timed out", ignoreCase = true) ||
            text.contains("network is unreachable", ignoreCase = true) ||
            text.contains("unreachable", ignoreCase = true)
    }

    fun userMessage(error: Throwable?): String = when {
        error == null -> "hotaudio extraction failed"
        isChallenge(error) ->
            "hotaudio Cloudflare clearance expired or invalid — refresh it in Settings"
        isRateLimit(error) ->
            "hotaudio rate limit reached (too many requests) — wait 20–30 seconds and try again"
        isTimeout(error) ->
            "hotaudio did not respond in time — the site may be throttling this network. " +
                "Wait a minute and try again."
        isDnsOrTransient(error) ->
            "hotaudio network/DNS failure (${detail(error)}). If your carrier blocks hotaudio, enable Private DNS (e.g. dns.google) or try another network."
        error is FfiException.Failed -> "hotaudio error (${error.code}): ${error.detail}"
        error is FfiException.Panic -> "hotaudio error: ${error.detail}"
        else -> error.message ?: error.toString()
    }
}
