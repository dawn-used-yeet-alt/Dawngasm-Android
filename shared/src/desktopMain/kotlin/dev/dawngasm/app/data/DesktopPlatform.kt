package dev.dawngasm.app.data

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Per-OS locations for desktop state. Everything is derived from environment
 * variables and `user.home`, so there are no `windowsMain`/`macosMain` source
 * sets: one desktop implementation detects the OS at runtime.
 *
 * Tests and portable installs can override any directory with
 * `DAWNGASM_CONFIG_DIR`, `DAWNGASM_CACHE_DIR`, or `DAWNGASM_MUSIC_DIR`.
 */
object DesktopPlatform {
    enum class Os {
        LINUX,
        WINDOWS,
        MACOS,
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun pathEnv(name: String): Path? = env(name)?.let { Paths.get(it).toAbsolutePath().normalize() }

    private val osName: String = System.getProperty("os.name").orEmpty()

    val os: Os = when {
        osName.contains("mac", ignoreCase = true) -> Os.MACOS
        osName.contains("win", ignoreCase = true) -> Os.WINDOWS
        else -> Os.LINUX
    }

    /** Normalized architecture name used by the Phase 17 native resource layout. */
    val arch: String = when (val raw = System.getProperty("os.arch").orEmpty().lowercase()) {
        "amd64", "x86_64", "x64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        "x86", "i386", "i686" -> "x86"
        else -> raw.ifBlank { "unknown" }
    }

    val platformKey: String = when (os) {
        Os.LINUX -> "linux"
        Os.WINDOWS -> "windows"
        Os.MACOS -> "macos"
    }

    /** `linux-x64`, `windows-x64`, `macos-arm64`, ... */
    val nativePlatformDir: String = "$platformKey-$arch"

    private val home: Path = Paths.get(System.getProperty("user.home") ?: ".").toAbsolutePath().normalize()

    val configDir: Path = pathEnv("DAWNGASM_CONFIG_DIR")
        ?: when (os) {
            Os.LINUX -> (pathEnv("XDG_CONFIG_HOME") ?: home.resolve(".config")).resolve("dawngasm")
            Os.WINDOWS -> (pathEnv("APPDATA") ?: home.resolve("AppData").resolve("Roaming")).resolve("Dawngasm")
            Os.MACOS -> home.resolve("Library").resolve("Application Support").resolve("Dawngasm")
        }

    val cacheDir: Path = pathEnv("DAWNGASM_CACHE_DIR")
        ?: when (os) {
            Os.LINUX -> (pathEnv("XDG_CACHE_HOME") ?: home.resolve(".cache")).resolve("dawngasm")
            Os.WINDOWS -> (pathEnv("LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local"))
                .resolve("Dawngasm").resolve("cache")
            Os.MACOS -> home.resolve("Library").resolve("Caches").resolve("Dawngasm")
        }

    val musicDir: Path = pathEnv("DAWNGASM_MUSIC_DIR")
        ?: home.resolve("Music").resolve("Dawngasm")

    /** Native libraries bundled by Phase 17 live below `native/<os>-<arch>/`. */
    val nativeDir: Path = configDir.resolve("native")
    val nativePlatformPath: Path = nativeDir.resolve(nativePlatformDir)

    val logsDir: Path = configDir.resolve("logs")
    val settingsFile: Path = configDir.resolve("settings.json")

    val downloadDir: Path = musicDir

    /** Short label shown in Settings/About. */
    val displayName: String = when (os) {
        Os.LINUX -> "Linux ($arch)"
        Os.WINDOWS -> "Windows ($arch)"
        Os.MACOS -> "macOS ($arch)"
    }

    fun ensureDirectories() {
        listOf(configDir, cacheDir, musicDir, nativeDir, nativePlatformPath, logsDir, downloadDir)
            .forEach { dir ->
                runCatching { java.nio.file.Files.createDirectories(dir) }
            }
    }

    /** Reveals [directory] in the platform file manager. Returns false if no opener worked. */
    fun openDirectory(directory: Path): Boolean {
        runCatching { java.nio.file.Files.createDirectories(directory) }
        if (java.awt.Desktop.isDesktopSupported()) {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                val opened = runCatching { desktop.open(directory.toFile()) }.isSuccess
                if (opened) return true
            }
        }
        val command = when (os) {
            Os.LINUX -> listOf("xdg-open", directory.toString())
            Os.WINDOWS -> listOf("explorer.exe", directory.toString())
            Os.MACOS -> listOf("open", directory.toString())
        }
        return runCatching { ProcessBuilder(command).start(); true }.getOrDefault(false)
    }
}
