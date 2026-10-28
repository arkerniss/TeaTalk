package com.vertin.teatalk.core.server

import com.vertin.teatalk.core.engine.ChatEngine
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LanServer(
    private val config: ServerConfig,
) {
    val engine = ChatEngine(
        dataDir = config.dataDir,
        roomName = config.roomName,
        appVersion = config.appVersion,
        maxFileBytes = config.maxFileBytes,
        fileRetentionMs = config.fileRetentionMs,
    )
    val state = engine.state
    val port: Int get() = config.port

    private val hub = WsHub(engine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    suspend fun start() {
        check(server == null) { "server already started" }
        engine.start()
        scope.launch {
            engine.events.collect { event -> hub.broadcast(event) }
        }
        scope.launch {
            while (isActive) {
                delay(5L * 60L * 1000L)
                runCatching { engine.purgeExpiredFiles() }
            }
        }
        server = embeddedServer(CIO, host = "0.0.0.0", port = config.port) {
            install(WebSockets) {
                pingPeriodMillis = 20_000L
                timeoutMillis = 40_000L
                maxFrameSize = 128L * 1024L
            }
            installRoutes(config, this@LanServer.engine, hub)
        }.start(wait = false)
    }

    suspend fun clear() = engine.clearHistory()

    fun stop() {
        server?.stop(500, 1_000)
        server = null
        scope.cancel()
        engine.close()
    }
}
