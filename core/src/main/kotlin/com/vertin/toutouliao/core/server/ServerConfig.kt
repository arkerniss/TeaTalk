package com.vertin.toutouliao.core.server

import java.io.File

data class ServerConfig(
    val port: Int,
    val dataDir: File,
    val assetSource: AssetSource,
    val roomName: String = "我的局域网",
    val appId: String = "toutouliao",
    val appVersion: String = "0.1.0",
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
)
