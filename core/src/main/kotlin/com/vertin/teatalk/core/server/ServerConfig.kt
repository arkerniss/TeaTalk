package com.vertin.teatalk.core.server

import java.io.File

data class ServerConfig(
    val port: Int,
    val dataDir: File,
    val assetSource: AssetSource,
    val roomName: String = "我的局域网",
    val appId: String = "teatalk",
    val appVersion: String = "0.1.5",
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
)
