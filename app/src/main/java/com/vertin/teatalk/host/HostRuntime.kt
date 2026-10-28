package com.vertin.teatalk.host

import android.content.Context
import com.vertin.teatalk.AndroidAssetSource
import com.vertin.teatalk.BuildConfig
import com.vertin.teatalk.core.server.LanServer
import com.vertin.teatalk.core.server.ServerConfig
import com.vertin.teatalk.discovery.AndroidLanDiscovery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Process-wide owner of the LAN room. HostActivity only renders/drives it; the
 * foreground service keeps the process alive. This is why destroying or
 * recreating the Activity no longer takes the Ktor server down with it.
 */
object HostRuntime {
    private val startMutex = Mutex()

    @Volatile
    private var serverRef: LanServer? = null

    @Volatile
    private var discoveryRef: AndroidLanDiscovery? = null

    @Volatile
    private var started = false

    val server: LanServer? get() = serverRef
    val isRunning: Boolean get() = started

    @Synchronized
    fun ensureServer(context: Context): LanServer {
        serverRef?.let { return it }
        val created = LanServer(
            ServerConfig(
                port = 8520,
                dataDir = File(context.filesDir, "teatalk"),
                assetSource = AndroidAssetSource(context.applicationContext.assets),
                roomName = "我的局域网",
                appId = "teatalk",
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
        serverRef = created
        return created
    }

    suspend fun start(context: Context): LanServer = startMutex.withLock {
        val server = ensureServer(context)
        if (started) return@withLock server

        server.start()
        val discovery = AndroidLanDiscovery(context.applicationContext)
        runCatching {
            discovery.startAdvertising(
                roomName = server.state.value.room.name.ifBlank { "我的局域网" },
                port = server.port,
                hostId = UUID.randomUUID().toString(),
            )
        }.onFailure {
            discovery.close()
        }.onSuccess {
            discoveryRef = discovery
        }
        started = true
        server
    }

    @Synchronized
    fun stop() {
        started = false
        discoveryRef?.let { runCatching { it.close() } }
        discoveryRef = null
        serverRef?.let { runCatching { it.stop() } }
        serverRef = null
    }
}
