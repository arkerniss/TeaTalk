package com.vertin.teatalk.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

enum class AddressKind(val label: String, val rank: Int) {
    WIFI("Wi-Fi", 0),
    HOTSPOT("热点", 1),
    ETHERNET("以太网", 2),
    OTHER("其他网卡", 3),
    CELLULAR("移动网络", 4),
    VPN("VPN/代理", 5),
}

data class LanCandidate(
    val ip: String,
    val interfaceName: String,
    val kind: AddressKind,
    val isPrivate: Boolean,
)

data class NetworkInspection(
    val candidates: List<LanCandidate>,
    val recommendedIndex: Int,
    val vpnActive: Boolean,
    val wifiActive: Boolean,
    val cellularActive: Boolean,
    val advice: List<String>,
)

/**
 * Collects LAN IPv4 candidates and prefers real Wi-Fi/hotspot/ethernet addresses.
 * VPN and cellular addresses are shown for diagnostics but never recommended.
 */
object NetworkAddresses {
    fun inspect(context: Context): NetworkInspection {
        val candidatesByIp = LinkedHashMap<String, LanCandidate>()
        var wifiActive = false
        var vpnActive = false
        var cellularActive = false

        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            val networks = manager.allNetworks?.toList().orEmpty()
            for (network in networks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                val link = manager.getLinkProperties(network) ?: continue
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (isVpn) vpnActive = true
                if (isWifi) wifiActive = true
                if (isCellular) cellularActive = true
                val kind = when {
                    isVpn -> AddressKind.VPN
                    isWifi -> AddressKind.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> AddressKind.ETHERNET
                    isCellular -> AddressKind.CELLULAR
                    else -> AddressKind.OTHER
                }
                for (linkAddress in link.linkAddresses) {
                    val address = linkAddress.address
                    if (address is Inet4Address) {
                        addCandidate(candidatesByIp, address, link.interfaceName.orEmpty(), kind)
                    }
                }
            }
            val activeCaps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) vpnActive = true
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) wifiActive = true
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) cellularActive = true
        }

        // Fallback/extra coverage for hotspot interfaces (ap0/softap0) and OEM names.
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .forEach { nif ->
                    val kind = kindForName(nif.name)
                    for (address in nif.inetAddresses.toList()) {
                        if (address is Inet4Address) {
                            addCandidate(candidatesByIp, address, nif.name, kind)
                        }
                    }
                }
        }

        val sorted = candidatesByIp.values.sortedWith(
            compareBy<LanCandidate> { it.kind.rank }
                .thenByDescending { it.isPrivate }
                .thenBy { it.ip },
        )
        val recommendedIndex = sorted.indexOfFirst {
            it.isPrivate && it.kind != AddressKind.VPN && it.kind != AddressKind.CELLULAR
        }.let { index ->
            if (index >= 0) index else sorted.indexOfFirst { it.kind != AddressKind.VPN }.let { it.coerceAtLeast(0) }
        }

        val advice = buildList {
            if (vpnActive) {
                add("检测到 VPN/代理。VPN 可能拦截局域网访问，先关闭 VPN/代理再让另一台设备重试。")
            }
            if (!wifiActive && sorted.none { it.kind == AddressKind.HOTSPOT }) {
                add("未检测到 Wi-Fi 地址。请让主机连上和对方同一个 Wi-Fi，或打开手机热点。")
            }
            if (sorted.isEmpty()) {
                add("没有可用的 IPv4 地址；先连接 Wi-Fi/热点，再点“刷新网络”。")
            }
            if (sorted.any { !it.isPrivate }) {
                add("列表里有非私网地址（公网/运营商），其他设备通常无法访问，优先选 Wi-Fi 地址。")
            }
            add("两台设备必须连同一个 Wi-Fi；公司/酒店/访客 Wi-Fi 可能开启 AP 隔离，换热点测试可快速排除。")
        }

        return NetworkInspection(
            candidates = sorted,
            recommendedIndex = recommendedIndex,
            vpnActive = vpnActive,
            wifiActive = wifiActive,
            cellularActive = cellularActive,
            advice = advice,
        )
    }

    private fun addCandidate(
        target: MutableMap<String, LanCandidate>,
        address: Inet4Address,
        interfaceName: String,
        kind: AddressKind,
    ) {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return
        val ip = address.hostAddress ?: return
        val candidate = LanCandidate(
            ip = ip,
            interfaceName = interfaceName.ifBlank { "未知网卡" },
            kind = kind,
            isPrivate = isPrivateV4(address),
        )
        val existing = target[ip]
        if (existing == null || candidate.kind.rank < existing.kind.rank) {
            target[ip] = candidate
        }
    }

    private fun isPrivateV4(address: Inet4Address): Boolean {
        if (address.isSiteLocalAddress) return true
        // 100.64.0.0/10 (CGNAT), common on carrier Wi-Fi and some VPNs.
        val bytes = address.address.map { it.toInt() and 0xff }
        return bytes[0] == 100 && bytes[1] in 64..127
    }

    private fun kindForName(name: String): AddressKind = when {
        name.startsWith("wlan") || name.startsWith("wifi") -> AddressKind.WIFI
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> AddressKind.HOTSPOT
        name.startsWith("eth") || name.startsWith("en") -> AddressKind.ETHERNET
        name.contains("tun") ||
            name.contains("tap") ||
            name.startsWith("wg") ||
            name.contains("ppp") ||
            name.contains("vpn") ||
            name.contains("tailscale") ||
            name.contains("clash") -> AddressKind.VPN
        name.startsWith("rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("pdp") ||
            name.startsWith("v4-rmnet") -> AddressKind.CELLULAR
        else -> AddressKind.OTHER
    }
}
