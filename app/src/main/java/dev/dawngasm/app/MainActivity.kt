package dev.dawngasm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dawngasm.app.ui.theme.DawngasmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            net.hotaudio.extractor.Hotaudio.initAndroidTls(applicationContext)
        }
        setContent {
            DawngasmTheme {
                DawngasmApp()
            }
        }
    }
}
