package dev.dawngasm.app

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.CookieUtils
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.ui.screens.HotaudioClearanceScreen
import dev.dawngasm.app.ui.theme.DawngasmTheme

/**
 * Opens hotaudio.net in a real WebView so the user can pass Cloudflare's
 * challenge. The resulting `cf_clearance` cookie is bound to the WebView's
 * User-Agent and client IP family (IPv4 vs IPv6), so both are saved together
 * and handed to the native extractor.
 */
class HotaudioClearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DawngasmTheme {
                HotaudioClearanceScreen(
                    onClearanceCaptured = { rawCookie, userAgent, ip, family ->
                        captureClearance(rawCookie, userAgent, ip, family)
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun captureClearance(
        rawCookie: String,
        userAgent: String,
        ip: String?,
        family: String?,
    ): Boolean {
        HotaudioLog.i("captureClearance: begin ip=$ip family=$family")
        val hasClearanceCookie = CookieUtils.hasCookie(rawCookie, "cf_clearance")
        HotaudioLog.i(
            "captureClearance: mergedLen=${rawCookie.length} hasCfClearance=$hasClearanceCookie",
        )
        if (!hasClearanceCookie) {
            HotaudioLog.w("captureClearance: no cf_clearance cookie yet")
            Toast.makeText(
                this,
                "Cloudflare has not cleared yet — finish the check first",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }

        val prefs = AppPreferences(this)
        prefs.saveHotaudioClearance(rawCookie, userAgent)
        HotaudioLog.i("captureClearance: saved cookie+ua")

        if (family != null) {
            prefs.saveHotaudioIpFamily(family, ip)
            try {
                net.hotaudio.extractor.setIpFamily(family)
                HotaudioLog.i("captureClearance: setIpFamily($family) OK")
            } catch (error: Throwable) {
                HotaudioLog.e("captureClearance: setIpFamily($family) failed", error)
            }
        } else {
            HotaudioLog.w("captureClearance: no IP family detected")
        }

        try {
            net.hotaudio.extractor.setClearance(rawCookie, userAgent)
            HotaudioLog.i("captureClearance: setClearance OK")
        } catch (error: Throwable) {
            HotaudioLog.e("captureClearance: setClearance failed", error)
        }

        CookieManager.getInstance().flush()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CONNECTED, true))
        finish()
        return true
    }

    companion object {
        const val EXTRA_CONNECTED = "hotaudio_connected"
    }
}
