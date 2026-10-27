package com.vertin.teatalk.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.vertin.teatalk.core.discovery.DiscoveredRoom
import com.vertin.teatalk.core.discovery.LanBeaconPublisher
import com.vertin.teatalk.core.discovery.LanBeaconScanner
import com.vertin.teatalk.core.discovery.RoomBeacon
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/**
 * Android lifecycle/network wrapper around the pure-JVM discovery sockets. The
 * multicast lock is required on many Wi-Fi stacks, especially with screen-off
 * power saving, for UDP broadcast/multicast traffic to reach the app.
 */
class AndroidLanDiscovery(context: Context) : Closeable {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var publisher: LanBeaconPublisher? = null
    private var scanner: LanBeaconScanner? = null

    fun startAdvertising(roomName: String, port: Int, hostId: String) {
        stopAdvertising()
        acquireMulticastLock()
        try {
            publisher = LanBeaconPublisher(
                RoomBeacon(hostId = hostId, room = roomName, port = port),
            ).also { it.start() }
        } catch (error: Throwable) {
            stopAdvertising()
            throw error
        }
    }

    fun startScanning(): StateFlow<List<DiscoveredRoom>> {
        stopScanning()
        acquireMulticastLock()
        return try {
            scanner = LanBeaconScanner().also { it.start() }
            scanner!!.rooms
        } catch (error: Throwable) {
            stopScanning()
            throw error
        }
    }

    fun stopAdvertising() {
        runCatching { publisher?.close() }
        publisher = null
        releaseMulticastLockIfIdle()
    }

    fun stopScanning() {
        runCatching { scanner?.close() }
        scanner = null
        releaseMulticastLockIfIdle()
    }

    override fun close() {
        stopAdvertising()
        stopScanning()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            wifiManager?.createMulticastLock("TeaTalkDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()?.let { multicastLock = it }
    }

    private fun releaseMulticastLockIfIdle() {
        if (publisher == null && scanner == null) {
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            multicastLock = null
        }
    }
}
