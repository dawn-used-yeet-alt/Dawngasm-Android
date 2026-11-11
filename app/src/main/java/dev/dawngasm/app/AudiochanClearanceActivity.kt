package dev.dawngasm.app

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.CookieUtils
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.ui.screens.AudiochanClearanceScreen
import dev.dawngasm.app.ui.theme.DawngasmTheme

/**
 * Opens audiochan.com in a WebView to let the user pass the Cloudflare
 * Turnstile playback verification check. The resulting clearance cookie
 * and User-Agent are saved for OkHttp playback and downloads.
 */
class AudiochanClearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DawngasmTheme {
                AudiochanClearanceScreen(
                    onClearanceCaptured = { rawCookie, userAgent ->
                        captureClearance(rawCookie, userAgent)
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun captureClearance(rawCookie: String, userAgent: String): Boolean {
        HotaudioLog.i("captureAudiochanClearance: begin")
        val hasClearanceCookie = CookieUtils.hasCookie(rawCookie, "__Secure-audiochan_playback")
        HotaudioLog.i(
            "captureAudiochanClearance: mergedLen=${rawCookie.length} hasClearance=$hasClearanceCookie",
        )

        val prefs = AppPreferences(this)
        prefs.saveAudiochanClearance(rawCookie, userAgent)
        Network.syncCookie(AUDIOCHAN_URL, rawCookie)
        Network.syncCookie(AUDIOCHAN_API_URL, rawCookie)
        CookieManager.getInstance().flush()
        HotaudioLog.i("captureAudiochanClearance: saved clearance")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CONNECTED, true))
        finish()
        return true
    }

    companion object {
        const val EXTRA_CONNECTED = "audiochan_connected"
        private const val AUDIOCHAN_URL = "https://audiochan.com/"
        private const val AUDIOCHAN_API_URL = "https://api.audiochan.com/"
    }
}
