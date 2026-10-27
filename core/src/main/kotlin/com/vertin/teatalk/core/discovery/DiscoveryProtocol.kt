package com.vertin.teatalk.core.discovery

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** A discoverable room. The source IP is deliberately not part of the advertised payload. */
data class RoomBeacon(
    val hostId: String,
    val room: String,
    val port: Int,
)

data class DiscoveredRoom(
    val hostId: String,
    val room: String,
    val ip: String,
    val port: Int,
    val lastSeen: Long,
)

sealed interface DiscoveryPacket {
    data class Room(val beacon: RoomBeacon, val sourceIp: String) : DiscoveryPacket
    data object Probe : DiscoveryPacket
}

object DiscoveryProtocol {
    const val APP_ID = "teatalk"
    const val VERSION = 1
    const val DISCOVERY_PORT = 18522
    const val MAX_PACKET_BYTES = 512
    const val BEACON_INTERVAL_MS = 2_000L
    const val PROBE_INTERVAL_MS = 3_000L
    const val STALE_AFTER_MS = 7_000L
    const val MAX_ROOM_NAME = 40
    const val MAX_HOST_ID = 64

    private val gson = Gson()

    fun encodeRoom(beacon: RoomBeacon): ByteArray {
        require(beacon.room.isNotBlank()) { "room name is blank" }
        require(beacon.port in 1..65535) { "invalid port" }
        require(beacon.hostId.length in 1..MAX_HOST_ID) { "invalid hostId" }
        val json = JsonObject().apply {
            addProperty("app", APP_ID)
            addProperty("v", VERSION)
            addProperty("type", "room")
            addProperty("hostId", beacon.hostId)
            addProperty("room", beacon.room.take(MAX_ROOM_NAME))
            addProperty("port", beacon.port)
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_PACKET_BYTES) { "packet too large" }
        return json
    }

    fun encodeProbe(): ByteArray = JsonObject().apply {
        addProperty("app", APP_ID)
        addProperty("v", VERSION)
        addProperty("type", "probe")
    }.toString().toByteArray(Charsets.UTF_8)

    fun parse(payload: ByteArray, sourceAddress: InetAddress? = null): DiscoveryPacket? {
        if (payload.isEmpty() || payload.size > MAX_PACKET_BYTES) return null
        val root = runCatching {
            gson.fromJson(payload.toString(Charsets.UTF_8), JsonObject::class.java)
        }.getOrNull() ?: return null
        if (root.string("app") != APP_ID) return null
        if (root.int("v") != VERSION) return null
        return when (root.string("type")) {
            "probe" -> DiscoveryPacket.Probe
            "room" -> {
                val hostId = root.string("hostId")
                    ?.filterNot { it.isISOControl() }
                    ?.trim()
                    ?.take(MAX_HOST_ID)
                    .orEmpty()
                val room = root.string("room")
                    ?.filterNot { it.isISOControl() }
                    ?.trim()
                    ?.take(MAX_ROOM_NAME)
                    .orEmpty()
                val port = root.int("port") ?: return null
                if (hostId.isBlank() || room.isBlank() || port !in 1..65535) return null
                DiscoveryPacket.Room(
                    beacon = RoomBeacon(hostId = hostId, room = room, port = port),
                    sourceIp = sourceAddress?.hostAddress.orEmpty(),
                )
            }

            else -> null
        }
    }

    /** IPv4 subnet broadcast addresses; excludes VPN/cellular interfaces. */
    fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .filter { !isIgnoredInterface(it.name) }
                .forEach { nif ->
                    for (interfaceAddress in nif.interfaceAddresses) {
                        val address = interfaceAddress.address
                        val broadcast = interfaceAddress.broadcast ?: continue
                        if (address is Inet4Address && isPrivateV4(address) && !address.isLinkLocalAddress) {
                            targets.add(broadcast)
                        }
                    }
                }
        }
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        return targets.toList()
    }

    private fun isIgnoredInterface(name: String): Boolean =
        name.startsWith("tun") ||
            name.startsWith("tap") ||
            name.startsWith("ppp") ||
            name.startsWith("utun") ||
            name.startsWith("wg") ||
            name.startsWith("rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("pdp")

    private fun isPrivateV4(address: Inet4Address): Boolean {
        if (address.isSiteLocalAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        return bytes[0] == 100 && bytes[1] in 64..127
    }

    private fun JsonObject.string(name: String): String? =
        runCatching { get(name)?.asString }.getOrNull()

    private fun JsonObject.int(name: String): Int? =
        runCatching { get(name)?.asInt }.getOrNull()
}

/** Thread-safe, expiry-aware room index shared by the scanner and tests. */
class DiscoveredRoomTracker(
    private val staleAfterMs: Long = DiscoveryProtocol.STALE_AFTER_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val byHostId = LinkedHashMap<String, DiscoveredRoom>()

    @Synchronized
    fun update(beacon: RoomBeacon, sourceIp: String): List<DiscoveredRoom> {
        if (sourceIp.isBlank()) return snapshot()
        val now = clock()
        byHostId[beacon.hostId] = DiscoveredRoom(
            hostId = beacon.hostId,
            room = beacon.room,
            ip = sourceIp,
            port = beacon.port,
            lastSeen = now,
        )
        pruneLocked(now)
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): List<DiscoveredRoom> {
        pruneLocked(clock())
        return snapshotLocked()
    }

    private fun snapshotLocked(): List<DiscoveredRoom> = byHostId.values
        .sortedWith(compareByDescending<DiscoveredRoom> { it.lastSeen }.thenBy { it.room })

    private fun pruneLocked(now: Long) {
        byHostId.entries.removeAll { now - it.value.lastSeen > staleAfterMs }
    }
}
