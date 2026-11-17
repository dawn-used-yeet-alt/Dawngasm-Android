package dev.dawngasm.desktop

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** Build/runtime identity for the native desktop application. */
@OptIn(ExperimentalNativeApi::class)
internal object DesktopInfo {
    const val VERSION: String = "2.1.0"

    val osName: String = Platform.osFamily.name.lowercase()
    val archName: String = Platform.cpuArchitecture.name.lowercase()
}
