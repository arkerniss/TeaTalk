package com.vertin.teatalk

import android.content.res.AssetManager
import com.vertin.teatalk.core.server.AssetSource

/** Reads the web UI that Gradle packages from the repo's web/ directory. */
class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    override fun read(path: String): ByteArray? = runCatching {
        assets.open(path.removePrefix("/")).use { it.readBytes() }
    }.getOrNull()
}
