package dev.dawngasm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.ui.theme.DawngasmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // TLS and clearance are initialized in DawngasmApplication.onCreate so
        // every entry point (including the clearance WebView) gets them.
        HotaudioLog.i("MainActivity.onCreate")
        setContent {
            DawngasmTheme {
                DawngasmApp()
            }
        }
    }
}
