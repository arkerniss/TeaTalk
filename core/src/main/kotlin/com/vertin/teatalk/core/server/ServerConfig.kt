package com.vertin.teatalk.core.server

import java.io.File

data class ServerConfig(
    val port: Int,
    val dataDir: File,
    val assetSource: AssetSource,
    val roomName: String = "我的局域网",
    val appId: String = "teatalk",
    val appVersion: String = "0.1.7.2",
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
    val fileRetentionMs: Long = 7L * 24L * 60L * 60L * 1000L,
)
