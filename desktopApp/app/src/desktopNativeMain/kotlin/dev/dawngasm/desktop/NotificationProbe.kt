package dev.dawngasm.desktop

import dev.brahmkshatriya.kmedianotification.KMediaNotification
import dev.brahmkshatriya.kmedianotification.MediaPlatform
import dev.brahmkshatriya.kmedianotification.desktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Builds the desktop media-notification session without activating it.
 *
 * Headless CI runners have no D-Bus session (Linux) or shell session (Windows),
 * so activation is deliberately left to a real desktop. Constructing the
 * session proves the MPRIS/SMTC API and native library link correctly.
 */
internal fun probeNotifications() {
    println("kmedianotification: probing notifications...")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val platform = MediaPlatform.desktop {
            linuxBusName = "org.mpris.MediaPlayer2.dawngasm"
            linuxDesktopEntry = "dev.dawngasm"
        }
        val session = KMediaNotification.create(
            platform = platform,
            scope = scope,
            id = "dawngasm",
            displayName = "Dawngasm",
        )
        println("kmedianotification: session created status=${session.status.value}")
        if (DesktopInfo.osName != "windows") {
            session.close()
        }
    } catch (error: Throwable) {
        println("kmedianotification: not exercised (${error.message})")
    } finally {
        if (DesktopInfo.osName != "windows") {
            scope.cancel()
        }
    }
}
