package dev.dawngasm.app.data

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform as KonanPlatform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
object DesktopPlatform {
    enum class Os {
        LINUX,
        WINDOWS,
        MACOS,
    }

    private val osFamilyName: String = KonanPlatform.osFamily.name.lowercase()

    val os: Os = when {
        osFamilyName.contains("macos") || osFamilyName.contains("osx") -> Os.MACOS
        osFamilyName.contains("windows") || osFamilyName.contains("mingw") -> Os.WINDOWS
        else -> Os.LINUX
    }

    val arch: String = when (val a = KonanPlatform.cpuArchitecture.name.lowercase()) {
        "arm64", "aarch64" -> "arm64"
        "x64", "x86_64", "amd64" -> "x64"
        "x86", "i386", "i686" -> "x86"
        else -> a
    }

    val platformKey: String = when (os) {
        Os.LINUX -> "linux"
        Os.WINDOWS -> "windows"
        Os.MACOS -> "macos"
    }

    val nativePlatformDir: String = "$platformKey-$arch"

    val displayName: String = when (os) {
        Os.LINUX -> "Linux ($arch)"
        Os.WINDOWS -> "Windows ($arch)"
        Os.MACOS -> "macOS ($arch)"
    }

    fun env(name: String): String? =
        platform.posix.getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

    private val home: String =
        env("HOME") ?: env("USERPROFILE") ?: "."

    val configDir: String = env("DAWNGASM_CONFIG_DIR")
        ?: when (os) {
            Os.LINUX -> (env("XDG_CONFIG_HOME") ?: "$home/.config") + "/dawngasm"
            Os.WINDOWS -> (env("APPDATA") ?: "$home/AppData/Roaming") + "/Dawngasm"
            Os.MACOS -> "$home/Library/Application Support/Dawngasm"
        }

    val cacheDir: String = env("DAWNGASM_CACHE_DIR")
        ?: when (os) {
            Os.LINUX -> (env("XDG_CACHE_HOME") ?: "$home/.cache") + "/dawngasm"
            Os.WINDOWS -> (env("LOCALAPPDATA") ?: "$home/AppData/Local") + "/Dawngasm/cache"
            Os.MACOS -> "$home/Library/Caches/Dawngasm"
        }

    val musicDir: String = env("DAWNGASM_MUSIC_DIR")
        ?: "$home/Music/Dawngasm"

    val nativeDir: String = "$configDir/native"
    val nativePlatformPath: String = "$nativeDir/$nativePlatformDir"
    val logsDir: String = "$configDir/logs"
    val settingsFile: String = "$configDir/settings.json"
    val downloadDir: String = musicDir

    fun ensureDirectories() {
        listOf(configDir, cacheDir, musicDir, nativeDir, nativePlatformPath, logsDir, downloadDir).forEach {
            PlatformFileOps.ensureDir(it)
        }
    }

    fun openDirectory(directory: String): Boolean {
        PlatformFileOps.ensureDir(directory)
        return when (os) {
            Os.MACOS -> platform.posix.system("open \"$directory\" &") == 0
            Os.WINDOWS -> platform.posix.system("explorer \"$directory\"") == 0
            Os.LINUX -> platform.posix.system("xdg-open \"$directory\" >/dev/null 2>&1 &") == 0
        }
    }
}
