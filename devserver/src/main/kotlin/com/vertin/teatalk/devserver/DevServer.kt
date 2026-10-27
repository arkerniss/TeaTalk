package com.vertin.teatalk.devserver

import com.vertin.teatalk.core.server.FileAssetSource
import com.vertin.teatalk.core.server.LanServer
import com.vertin.teatalk.core.server.ServerConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() {
    runBlocking {
    val dataDir = File("build/dev-data")
    val server = LanServer(
        ServerConfig(
            port = 18520,
            dataDir = dataDir,
            assetSource = FileAssetSource(File("web")),
            roomName = "茶室开发室",
        ),
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.start()
    println("茶室 devserver: http://127.0.0.1:18520/")
    println("health: http://127.0.0.1:18520/api/health")
    System.out.flush()
    awaitCancellation()
    }
}
