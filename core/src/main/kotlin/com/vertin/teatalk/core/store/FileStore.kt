package com.vertin.teatalk.core.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

class FileTooLargeException(val maxBytes: Long) : Exception("file exceeds $maxBytes bytes")
class UploadSizeMismatchException(val expected: Long, val actual: Long) :
    Exception("expected $expected bytes, received $actual")

/**
 * Files are stored under files/<random id>, never under a user-supplied name.
 * The original display name lives in the message metadata.
 */
class FileStore(
    private val root: File,
) {
    private val filesDir = File(root, "files")
    private val tmpDir = File(root, "tmp")
    private val random = SecureRandom()
    private val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    init {
        filesDir.mkdirs()
        tmpDir.mkdirs()
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    fun newId(): String = buildString(16) {
        repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) }
    }

    fun isValidId(id: String): Boolean = id.length in 8..32 && id.all { it.isLetterOrDigit() }

    fun file(id: String): File? {
        if (!isValidId(id)) return null
        val candidate = File(filesDir, id)
        return candidate.takeIf { it.isFile }
    }

    fun delete(id: String) {
        file(id)?.delete()
    }

    suspend fun save(
        id: String,
        input: InputStream,
        maxBytes: Long,
        dispatcher: CoroutineDispatcher,
    ): Long = withContext(dispatcher) {
        require(isValidId(id)) { "invalid file id" }
        val temp = File(tmpDir, "$id.part")
        var total = 0L
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw FileTooLargeException(maxBytes)
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
            val destination = File(filesDir, id)
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            total
        } catch (error: Throwable) {
            temp.delete()
            throw error
        } finally {
            runCatching { input.close() }
        }
    }

    fun clear() {
        filesDir.listFiles()?.forEach { it.delete() }
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    fun usedBytes(): Long = filesDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun freeBytes(): Long = root.usableSpace.coerceAtLeast(0L)
}
