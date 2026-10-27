package com.vertin.teatalk.core.store

import com.google.gson.Gson
import java.io.File
import java.util.UUID

data class RoomMeta(
    val epoch: String,
    val createdAt: Long,
)

/** Persists the room epoch. Rotating the epoch tells clients to drop cached history. */
class RoomMetaStore(
    private val file: File,
    private val gson: Gson = Gson(),
) {
    init {
        file.parentFile?.mkdirs()
    }

    fun loadOrCreate(now: Long = System.currentTimeMillis()): RoomMeta {
        if (file.isFile) {
            runCatching { gson.fromJson(file.readText(Charsets.UTF_8), RoomMeta::class.java) }
                .getOrNull()
                ?.let { if (it.epoch.isNotBlank()) return it }
        }
        val meta = RoomMeta(epoch = UUID.randomUUID().toString(), createdAt = now)
        write(meta)
        return meta
    }

    fun rotate(now: Long = System.currentTimeMillis()): RoomMeta {
        val meta = RoomMeta(epoch = UUID.randomUUID().toString(), createdAt = now)
        write(meta)
        return meta
    }

    private fun write(meta: RoomMeta) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(gson.toJson(meta), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
