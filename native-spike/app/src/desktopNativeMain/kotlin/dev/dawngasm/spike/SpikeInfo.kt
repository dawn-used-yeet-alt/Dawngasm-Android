package dev.dawngasm.spike

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** Build/runtime identity for the native spike. */
@OptIn(ExperimentalNativeApi::class)
internal object SpikeInfo {
    const val VERSION: String = "0.1.0"

    val osName: String = Platform.osFamily.name.lowercase()
    val archName: String = Platform.cpuArchitecture.name.lowercase()
}
