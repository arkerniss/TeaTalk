package com.vertin.toutouliao.core.store

import com.google.gson.Gson
import com.vertin.toutouliao.core.model.Message
import java.io.File

/** Append-only JSONL message log. A malformed line is skipped, never fatal. */
class MessageLog(
    private val file: File,
    private val gson: Gson = Gson(),
) {
    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun append(message: Message) {
        file.parentFile?.mkdirs()
        file.appendText(gson.toJson(message) + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun loadLast(limit: Int): List<Message> {
        if (!file.isFile) return emptyList()
        val messages = ArrayList<Message>()
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            runCatching { gson.fromJson(line, Message::class.java) }
                .getOrNull()
                ?.let { messages.add(it) }
        }
        return if (messages.size <= limit) messages else messages.subList(messages.size - limit, messages.size)
    }

    @Synchronized
    fun clear() {
        file.parentFile?.mkdirs()
        file.writeText("", Charsets.UTF_8)
    }

    @Synchronized
    fun sizeBytes(): Long = if (file.isFile) file.length() else 0L
}
