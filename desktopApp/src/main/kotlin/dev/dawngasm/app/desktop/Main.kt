package dev.dawngasm.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dawngasm.app.ui.theme.DawngasmTheme
import java.awt.Dimension

fun main() = application {
    val windowState = rememberWindowState(width = 420.dp, height = 860.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Dawngasm",
    ) {
        window.minimumSize = Dimension(420, 640)
        DawngasmTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Dawngasm desktop")
            }
        }
    }
}
