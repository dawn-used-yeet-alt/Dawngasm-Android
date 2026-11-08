package dev.dawngasm.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dawngasm.app.ui.theme.DawngasmTheme
import java.io.File
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            net.hotaudio.extractor.Hotaudio.initAndroidTls(applicationContext)
        }.onFailure { error ->
            Log.e("MainActivity", "Failed to init Android TLS", error)
            if (BuildConfig.DEBUG) {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val stackTrace = Log.getStackTraceString(error)
                    val text = "TLS init error: ${error.message}\n$stackTrace"
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Dawngasm Error", text))
                    val dir = getExternalFilesDir(null) ?: filesDir
                    File(dir, "last_error.log").writeText("${Date()}\n$text\n")
                    File(filesDir, "last_error.log").writeText("${Date()}\n$text\n")
                } catch (_: Throwable) {}
            }
        }
        setContent {
            DawngasmTheme {
                DawngasmApp()
            }
        }
    }
}
