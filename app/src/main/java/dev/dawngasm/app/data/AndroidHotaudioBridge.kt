package dev.dawngasm.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.hotaudio.extractor.CancellationToken
import net.hotaudio.extractor.DownloadOptions
import net.hotaudio.extractor.Progress
import net.hotaudio.extractor.ProgressListener

class AndroidHotaudioBridge : HotaudioBridge {
    override fun setClearance(cookie: String, userAgent: String) {
        net.hotaudio.extractor.setClearance(cookie, userAgent)
    }

    override fun clearClearance() {
        net.hotaudio.extractor.clearClearance()
    }

    override fun setIpFamily(family: String) {
        net.hotaudio.extractor.setIpFamily(family)
    }

    override fun downloadToFile(
        pageUrl: String,
        destinationPath: String,
        initialKeysJson: String?,
        onProgress: (phase: String, loaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): HotaudioDownloadResult {
        val token = CancellationToken()
        val scope = CoroutineScope(Dispatchers.IO)
        val cancelPoller = scope.launch {
            while (isActive) {
                if (isCancelled()) {
                    token.cancel()
                    break
                }
                delay(100)
            }
        }

        try {
            val options = DownloadOptions(
                trackId = null,
                apiBase = "https://hotaudio.net",
                delayMs = 0u,
                initialKeysJson = initialKeysJson,
            )
            val listener = object : ProgressListener {
                override fun onProgress(progress: Progress) {
                    onProgress(progress.phase, progress.loaded.toLong(), progress.total.toLong())
                }
            }
            val info = net.hotaudio.extractor.downloadToFile(
                pageUrl = pageUrl,
                path = destinationPath,
                options = options,
                progress = listener,
                cancel = token,
            )
            return HotaudioDownloadResult(info.keysJson, info.keyPages.toInt())
        } finally {
            cancelPoller.cancel()
        }
    }
}
