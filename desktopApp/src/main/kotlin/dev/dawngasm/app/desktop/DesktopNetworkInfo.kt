package dev.dawngasm.app.desktop

import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Detects which IP family the OS picks for hotaudio. The clearance window and
 * the native extractor must use the same family, otherwise a saved
 * `cf_clearance` is bound to the wrong source address.
 */
internal object DesktopNetworkInfo {
    data class Egress(val family: String?, val ip: String?)

    private const val HOST = "hotaudio.net"
    private const val PORT = 443
    private const val TIMEOUT_MS = 5_000

    fun detectHotaudioEgress(): Egress = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            val local = socket.localAddress
            val ipv6 = local is Inet6Address && !isIpv4Mapped(local.hostAddress.orEmpty())
            Egress(
                family = if (ipv6) "ipv6" else "ipv4",
                ip = local.hostAddress?.substringBefore('%'),
            )
        }
    }.getOrDefault(Egress(family = null, ip = null))

    /** `::ffff:192.0.2.1` is an IPv4 connection exposed through the v6 stack. */
    private fun isIpv4Mapped(address: String): Boolean =
        address.startsWith("::ffff:", ignoreCase = true) ||
            address.startsWith("0:0:0:0:0:ffff:", ignoreCase = true)
}
