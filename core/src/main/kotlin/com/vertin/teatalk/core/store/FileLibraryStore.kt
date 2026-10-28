package com.vertin.teatalk.core.store

import com.google.gson.Gson
import com.vertin.teatalk.core.model.FileRecord
import java.io.File

const val DEFAULT_FILE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
const val FILE_EXTENSION_MS = 7L * 24L * 60L * 60L * 1000L

/** Persists the room file library independently from the message log. */
class FileLibraryStore(
    private val file: File,
    private val gson: Gson = Gson(),
) {
    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun load(): List<FileRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), Array<FileRecord>::class.java)
                ?.filter { it.id.isNotBlank() && it.size >= 0L }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(records: Collection<FileRecord>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(gson.toJson(records.toList()), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
