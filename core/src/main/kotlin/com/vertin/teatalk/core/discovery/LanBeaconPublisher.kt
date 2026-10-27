package com.vertin.teatalk.core.discovery

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Advertises one room on UDP port 18522. The socket receives probes so a joining
 * device gets a room immediately instead of waiting for the next beacon interval.
 */
class LanBeaconPublisher(
    private val beacon: RoomBeacon,
    private val discoveryPort: Int = DiscoveryProtocol.DISCOVERY_PORT,
    private val targetProvider: () -> List<InetAddress> = { DiscoveryProtocol.broadcastTargets() },
) : Closeable {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        check(!running) { "publisher already started" }
        val newSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(discoveryPort))
        }
        socket = newSocket
        running = true
        thread = Thread({ loop(newSocket) }, "teatalk-beacon-publisher").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(socket: DatagramSocket) {
        var lastBeaconAt = 0L
        val buffer = ByteArray(1024)
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastBeaconAt >= DiscoveryProtocol.BEACON_INTERVAL_MS) {
                sendBeacon(socket, targetProvider(), discoveryPort)
                lastBeaconAt = now
            }
            try {
                socket.soTimeout = 500
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (DiscoveryProtocol.parse(packet.data.copyOf(packet.length)) == DiscoveryPacket.Probe) {
                    sendBeacon(socket, listOf(packet.address), packet.port)
                }
            } catch (_: SocketTimeoutException) {
                // normal: wake up and send the next beacon
            } catch (_: SocketException) {
                if (running) {
                    // A closed socket while stopping is expected; otherwise stop cleanly.
                    running = false
                }
            } catch (_: Throwable) {
                // Never let one malformed packet kill room discovery.
            }
        }
    }

    private fun sendBeacon(socket: DatagramSocket, targets: List<InetAddress>, port: Int) {
        val bytes = DiscoveryProtocol.encodeRoom(beacon)
        for (target in targets) {
            runCatching {
                socket.send(DatagramPacket(bytes, bytes.size, target, port))
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
    }
}
