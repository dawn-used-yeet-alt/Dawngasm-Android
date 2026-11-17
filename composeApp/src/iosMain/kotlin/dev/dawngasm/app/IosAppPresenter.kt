package dev.dawngasm.app

import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.data.IosFileSaver
import dev.dawngasm.app.player.IosAudioPlayer

/**
 * iOS composition root for the shared presenter. The feed/search/library
 * implementation lives in [AppPresenter]; only the resolver, file saver, and
 * player are platform-specific.
 */
fun IosAppPresenter(): AppPresenter {
    val prefs = AppPreferences()
    val resolver = AudioResolver(prefs)
    return AppPresenter(
        prefs = prefs,
        api = DawngasmApi(),
        resolver = resolver,
        fileSaver = IosFileSaver(),
        player = IosAudioPlayer(resolver),
    )
}
