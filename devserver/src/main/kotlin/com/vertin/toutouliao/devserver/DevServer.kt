package com.vertin.toutouliao.devserver

import com.vertin.toutouliao.core.server.FileAssetSource
import com.vertin.toutouliao.core.server.LanServer
import com.vertin.toutouliao.core.server.ServerConfig
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
            roomName = "偷偷聊开发室",
        ),
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.start()
    println("偷偷聊 devserver: http://127.0.0.1:18520/")
    println("health: http://127.0.0.1:18520/api/health")
    System.out.flush()
    awaitCancellation()
    }
}
