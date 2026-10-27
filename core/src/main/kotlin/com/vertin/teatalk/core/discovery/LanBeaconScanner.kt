package com.vertin.teatalk.core.discovery

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Listens for room beacons and probes hosts for an immediate response. */
class LanBeaconScanner(
    private val discoveryPort: Int = DiscoveryProtocol.DISCOVERY_PORT,
    private val targetProvider: () -> List<InetAddress> = { DiscoveryProtocol.broadcastTargets() },
    private val tracker: DiscoveredRoomTracker = DiscoveredRoomTracker(),
) : Closeable {
    private val mutableRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val rooms: StateFlow<List<DiscoveredRoom>> = mutableRooms.asStateFlow()

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        check(!running) { "scanner already started" }
        val newSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(discoveryPort))
        }
        socket = newSocket
        running = true
        thread = Thread({ loop(newSocket) }, "teatalk-beacon-scanner").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(socket: DatagramSocket) {
        var lastProbeAt = 0L
        val buffer = ByteArray(1024)
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastProbeAt >= DiscoveryProtocol.PROBE_INTERVAL_MS) {
                sendProbe(socket)
                lastProbeAt = now
            }
            try {
                socket.soTimeout = 500
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                when (val parsed = DiscoveryProtocol.parse(packet.data.copyOf(packet.length), packet.address)) {
                    is DiscoveryPacket.Room -> {
                        mutableRooms.value = tracker.update(parsed.beacon, parsed.sourceIp)
                    }

                    DiscoveryPacket.Probe -> Unit
                    null -> Unit
                }
            } catch (_: SocketTimeoutException) {
                // Keep the snapshot fresh and expire silent hosts.
                mutableRooms.value = tracker.snapshot()
            } catch (_: SocketException) {
                if (running) running = false
            } catch (_: Throwable) {
                // Ignore malformed traffic; discovery must never crash the app.
            }
        }
    }

    private fun sendProbe(socket: DatagramSocket) {
        val bytes = DiscoveryProtocol.encodeProbe()
        for (target in targetProvider()) {
            runCatching {
                socket.send(DatagramPacket(bytes, bytes.size, target, discoveryPort))
            }
        }
    }

    @Synchronized
    override fun close() {
        running = false
        socket?.close()
        socket = null
        thread?.join(500)
        thread = null
        mutableRooms.value = emptyList()
    }
}
