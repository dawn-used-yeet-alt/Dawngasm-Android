package dev.dawngasm.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.any { it == "--self-test" || it == "--version" }) {
        val skipAudio = args.contains("--skip-audio-probe")
        runSelfTest(skipAudio = skipAudio)
        return
    }

    application {
        Window(
            onCloseRequest = { exitApplication() },
            state = rememberWindowState(),
            title = "Dawngasm Native Spike",
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen()
                }
            }
        }
    }
}

private fun runSelfTest(skipAudio: Boolean = false) {
    println("dawngasm native spike ${SpikeInfo.VERSION}")
    println("os=${SpikeInfo.osName} arch=${SpikeInfo.archName}")

    val playerReady = if (skipAudio) {
        println("kmplayer: audio probe skipped (--skip-audio-probe)")
        true
    } else {
        probePlayer()
    }
    probeNotifications()

    if (!playerReady) {
        println("self-test: failed")
        exitProcess(1)
    }
    println("self-test: ok")
}

@Composable
private fun SpikeScreen() {
    var clicks by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Dawngasm Native Spike",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(text = "Compose Native window, no JVM required.")
        Button(onClick = { clicks++ }) {
            Text(if (clicks == 0) "Click me" else "Clicked $clicks times")
        }
    }
}
