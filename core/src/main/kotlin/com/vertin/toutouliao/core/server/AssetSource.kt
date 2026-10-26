package com.vertin.toutouliao.core.server

import java.io.File

interface AssetSource {
    fun read(path: String): ByteArray?
}

class FileAssetSource(private val root: File) : AssetSource {
    private val canonicalRoot: File = root.canonicalFile

    override fun read(path: String): ByteArray? {
        val clean = path.removePrefix("/")
        if (clean.isBlank() || clean.contains("..")) return null
        val file = File(canonicalRoot, clean)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) return null
        return canonical.takeIf { it.isFile }?.readBytes()
    }
}

class MapAssetSource(private val assets: Map<String, ByteArray>) : AssetSource {
    override fun read(path: String): ByteArray? = assets[path.removePrefix("/")]
}
