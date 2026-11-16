package dev.dawngasm.spike

import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMResult

/**
 * Creates a real platform player backend without opening media.
 *
 * Linux selects GStreamer, Windows selects Media Foundation, and macOS selects
 * AVFoundation. Returning false means the packaged native shell is missing a
 * runtime dependency, which is exactly what the CI self-test should catch.
 */
internal fun probePlayer(): Boolean {
    return when (val created = KMPlayer.create(KMPlayerConfig(requireHls = false))) {
        is KMResult.Success -> {
            val player = created.value
            val capabilities = player.capabilities
            println(
                "kmplayer: ok backend=${capabilities.backend} audio=${capabilities.audio} " +
                    "video=${capabilities.video} hls=${capabilities.hls} " +
                    "seeking=${capabilities.seeking}",
            )
            player.close()
            true
        }

        is KMResult.Failure -> {
            println("kmplayer: FAILED code=${created.error.code} message=${created.error.message}")
            false
        }
    }
}
