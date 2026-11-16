package dev.dawngasm.app.desktop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17 smoke test: the host `libhotaudio_ffi` built by Gradle must load
 * through JNA and answer the generated `version()` call. This catches missing
 * native libraries and ABI mismatches on every desktop runner.
 */
class HotaudioFfiTest {
    @Test
    fun `native hotaudio library loads`() {
        val directory = nativeDirectory()
        assertTrue("native directory missing: $directory", directory.isDirectory)

        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) {
                directory.absolutePath
            } else {
                directory.absolutePath + File.pathSeparator + existing
            },
        )

        val version = net.hotaudio.extractor.version()
        assertFalse("hotaudio version was blank", version.isBlank())
    }

    private fun nativeDirectory(): File {
        val os = System.getProperty("os.name").lowercase()
        val platform = when {
            os.contains("mac") -> "macos"
            os.contains("win") -> "windows"
            else -> "linux"
        }
        val arch = when (val raw = System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> raw
        }
        return File("build/native-resources/native/$platform-$arch")
    }
}
