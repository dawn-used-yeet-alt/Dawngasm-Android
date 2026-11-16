package dev.dawngasm.app.desktop

import dev.dawngasm.app.data.DesktopHotaudioBridge
import dev.dawngasm.app.data.DesktopPlatform
import dev.dawngasm.app.data.HotaudioBridgeProvider
import dev.dawngasm.app.util.PlatformLog
import java.io.File

/**
 * Installs the desktop hotaudio bridge after pointing JNA at the bundled
 * `libhotaudio_ffi` for this OS/architecture.
 *
 * Returns false when the native library is not present, in which case the
 * common code keeps the manual clearance path and hotaudio streaming reports
 * the usual unavailable error.
 */
object DesktopHotaudio {
    fun install(): Boolean {
        val directory = locateNativeDir() ?: run {
            PlatformLog.i(LOG_TAG, "hotaudio native library not found; FFI disabled")
            return false
        }
        prependJnaLibraryPath(directory)

        return runCatching {
            net.hotaudio.extractor.version()
            HotaudioBridgeProvider.bridge = DesktopHotaudioBridge()
            PlatformLog.i(LOG_TAG, "hotaudio FFI loaded from ${directory.absolutePath}")
            true
        }.getOrElse { error ->
            PlatformLog.e(LOG_TAG, "hotaudio FFI failed to load: ${error.message}")
            false
        }
    }

    private fun locateNativeDir(): File? {
        val dirName = DesktopPlatform.nativePlatformDir
        val candidates = buildList {
            System.getenv("DAWNGASM_HOTAUDIO_NATIVE_DIR")?.let { add(File(it)) }
            System.getProperty("compose.application.resources.dir")?.let {
                add(File(it, "native/$dirName"))
            }
            add(File(DesktopPlatform.nativePlatformPath.toFile(), "hotaudio"))

            val workingDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var cursor: File? = workingDir
            repeat(5) {
                val root = cursor ?: return@repeat
                add(File(root, "desktopApp/build/native-resources/native/$dirName"))
                add(File(root, "build/native-resources/native/$dirName"))
                cursor = root.parentFile
            }
        }
        return candidates.firstOrNull { dir ->
            dir.isDirectory && dir.listFiles().orEmpty().any { it.name.startsWith("libhotaudio_ffi") }
        }
    }

    private fun prependJnaLibraryPath(directory: File) {
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) {
                directory.absolutePath
            } else {
                directory.absolutePath + File.pathSeparator + existing
            },
        )
    }

    private const val LOG_TAG = "DesktopHotaudio"
}
