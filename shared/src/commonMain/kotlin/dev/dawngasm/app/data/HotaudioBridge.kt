package dev.dawngasm.app.data

/**
 * Platform bridge for native hotaudio extractor functions.
 * Android implements this via JNI/net.hotaudio.extractor.*.
 * iOS implements this via Swift/Hotaudio.swift linking HotaudioFFI.xcframework.
 */
interface HotaudioBridge {
    fun setClearance(cookie: String, userAgent: String)
    fun clearClearance()
    fun setIpFamily(family: String)

    @Throws(Exception::class)
    fun downloadToFile(
        pageUrl: String,
        destinationPath: String,
        initialKeysJson: String?,
        onProgress: (phase: String, loaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): HotaudioDownloadResult
}

data class HotaudioDownloadResult(
    val keysJson: String,
    val keyPages: Int,
)

object HotaudioBridgeProvider {
    var bridge: HotaudioBridge? = null
}
