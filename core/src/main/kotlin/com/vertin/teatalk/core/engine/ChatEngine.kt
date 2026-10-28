package com.vertin.teatalk.core.engine

import com.google.gson.Gson
import com.vertin.teatalk.core.model.FileMeta
import com.vertin.teatalk.core.model.FileRecord
import com.vertin.teatalk.core.model.Message
import com.vertin.teatalk.core.model.MessageKind
import com.vertin.teatalk.core.model.PublicUser
import com.vertin.teatalk.core.model.RoomEvent
import com.vertin.teatalk.core.model.RoomInfo
import com.vertin.teatalk.core.model.RoomState
import com.vertin.teatalk.core.model.User
import com.vertin.teatalk.core.store.DEFAULT_FILE_RETENTION_MS
import com.vertin.teatalk.core.store.FILE_EXTENSION_MS
import com.vertin.teatalk.core.store.FileLibraryStore
import com.vertin.teatalk.core.store.FileStore
import com.vertin.teatalk.core.store.MessageLog
import com.vertin.teatalk.core.store.RoomMeta
import com.vertin.teatalk.core.store.RoomMetaStore
import com.vertin.teatalk.core.store.UploadSizeMismatchException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-process room engine. All browser/WebView clients talk to the server
 * layer; the native host console only reads [state].
 */
class ChatEngine(
    private val dataDir: File,
    val roomName: String,
    val appVersion: String = "0.1.0",
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
    val fileRetentionMs: Long = DEFAULT_FILE_RETENTION_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gson = Gson()
    private val metaStore = RoomMetaStore(File(dataDir, "meta.json"), gson)
    private val log = MessageLog(File(dataDir, "messages.jsonl"), gson)
    private val fileStore = FileStore(dataDir)
    private val libraryStore = FileLibraryStore(File(dataDir, "file-library.json"), gson)
    private val fileLibrary = ConcurrentHashMap<String, FileRecord>()
    private val random = SecureRandom()
    private val mutex = Mutex()
    private val users = LinkedHashMap<String, User>()
    private val messages = ArrayDeque<Message>()

    private var meta: RoomMeta = RoomMeta("", 0L)
    private var startedAt: Long = 0L
    private var started = false
    private val nextId = AtomicLong(1L)

    private val _state = MutableStateFlow(
        RoomState(
            room = RoomInfo(
                name = roomName,
                epoch = "",
                version = appVersion,
                maxFileBytes = maxFileBytes,
                startedAt = 0L,
            ),
        ),
    )
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RoomEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RoomEvent> = _events.asSharedFlow()

    val dataRoot: File get() = dataDir

    suspend fun start() = withContext(ioDispatcher) {
        if (started) return@withContext
        dataDir.mkdirs()
        meta = metaStore.loadOrCreate(clock())
        startedAt = clock()
        val loaded = log.loadLast(MAX_MEMORY_MESSAGES)
        messages.clear()
        messages.addAll(loaded)
        val lastId = loaded.maxOfOrNull { it.id } ?: 0L
        nextId.set(maxOf(clock() * 1000L, lastId + 1L))
        loadFileLibrary(loaded)
        started = true
        publishState()
    }

    suspend fun join(id: String, name: String, token: String): User = mutex.withLock {
        check(started) { "engine not started" }
        val userId = sanitizeClientId(id)
        val user = User(
            id = userId,
            name = sanitizeName(name),
            joinedAt = clock(),
            sessionToken = token,
        )
        users[userId] = user
        publishState()
        _events.tryEmit(RoomEvent.UsersChanged(publicUsers()))
        user
    }

    suspend fun leave(id: String, token: String) {
        mutex.withLock<Unit> {
            val user = users[id] ?: return@withLock
            if (user.sessionToken != token) return@withLock
            users.remove(id)
            publishState()
            _events.tryEmit(RoomEvent.UsersChanged(publicUsers()))
        }
    }

    suspend fun rename(id: String, name: String) {
        mutex.withLock<Unit> {
            val user = users[id] ?: return@withLock
            users[id] = user.copy(name = sanitizeName(name))
            publishState()
            _events.tryEmit(RoomEvent.UsersChanged(publicUsers()))
        }
    }

    suspend fun sendText(id: String, rawText: String, clientMsgId: String?): Message =
        withContext(ioDispatcher) {
            mutex.withLock {
                val user = users[id] ?: throw IllegalStateException("user not joined")
                val text = rawText.trim()
                if (text.isEmpty()) throw IllegalArgumentException("empty text")
                if (text.length > MAX_TEXT_CHARS) throw IllegalArgumentException("text too long")
                val message = Message(
                    id = nextId.getAndIncrement(),
                    kind = MessageKind.TEXT,
                    senderId = user.id,
                    senderName = user.name,
                    text = text,
                    ts = clock(),
                    clientMsgId = clientMsgId,
                )
                log.append(message)
                addMessage(message)
                publishState()
                _events.tryEmit(RoomEvent.MessageAdded(message))
                message
            }
        }

    suspend fun addFile(
        id: String,
        declaredName: String,
        declaredMime: String,
        declaredSize: Long,
        input: InputStream,
        clientMsgId: String?,
    ): Message {
        val user = mutex.withLock { users[id] } ?: throw IllegalStateException("user not joined")
        val fileId = fileStore.newId()
        val name = sanitizeFileName(declaredName)
        val mime = sanitizeMime(declaredMime)
        val actualSize = fileStore.save(fileId, input, maxFileBytes, ioDispatcher)
        if (declaredSize >= 0L && actualSize != declaredSize) {
            fileStore.delete(fileId)
            throw UploadSizeMismatchException(declaredSize, actualSize)
        }
        return withContext(ioDispatcher) {
            mutex.withLock {
                val now = clock()
                val record = FileRecord(
                    id = fileId,
                    name = name,
                    mime = mime,
                    size = actualSize,
                    uploadedAt = now,
                    expiresAt = now + fileRetentionMs,
                    uploaderId = user.id,
                    uploaderName = user.name,
                )
                fileLibrary[fileId] = record
                try {
                    libraryStore.save(fileLibrary.values)
                } catch (error: Throwable) {
                    fileLibrary.remove(fileId)
                    fileStore.delete(fileId)
                    throw error
                }
                val message = Message(
                    id = nextId.getAndIncrement(),
                    kind = MessageKind.FILE,
                    senderId = user.id,
                    senderName = user.name,
                    file = FileMeta(
                        id = fileId,
                        name = name,
                        size = actualSize,
                        mime = mime,
                    ),
                    ts = now,
                    clientMsgId = clientMsgId,
                )
                log.append(message)
                addMessage(message)
                publishState()
                _events.tryEmit(RoomEvent.MessageAdded(message))
                _events.tryEmit(RoomEvent.FilesChanged)
                message
            }
        }
    }

    suspend fun clearHistory() = withContext(ioDispatcher) {
        mutex.withLock {
            meta = metaStore.rotate(clock())
            log.clear()
            messages.clear()
            publishState()
            _events.tryEmit(RoomEvent.Cleared(currentRoom()))
        }
    }

    /** Backwards-compatible alias; intentionally keeps the independent file library. */
    suspend fun clear() = clearHistory()

    suspend fun listLibrary(): List<FileRecord> {
        purgeExpiredFiles()
        return fileLibrary.values.sortedWith(
            compareBy<FileRecord> { it.expiresAt }.thenByDescending { it.uploadedAt },
        )
    }

    suspend fun extendFile(fileId: String): FileRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            val record = fileLibrary[fileId] ?: return@withLock null
            val now = clock()
            if (record.expiresAt <= now) {
                fileLibrary.remove(fileId)
                fileStore.delete(fileId)
                libraryStore.save(fileLibrary.values)
                publishState()
                _events.tryEmit(RoomEvent.FilesChanged)
                return@withLock null
            }
            val updated = record.copy(expiresAt = maxOf(record.expiresAt, now) + FILE_EXTENSION_MS)
            fileLibrary[fileId] = updated
            libraryStore.save(fileLibrary.values)
            _events.tryEmit(RoomEvent.FilesChanged)
            updated
        }
    }

    suspend fun purgeExpiredFiles(): Int = withContext(ioDispatcher) {
        mutex.withLock {
            val removed = purgeExpiredLocked(clock())
            if (removed > 0) {
                publishState()
                _events.tryEmit(RoomEvent.FilesChanged)
            }
            removed
        }
    }

    fun findFile(fileId: String): FileRecord? =
        fileLibrary[fileId]?.takeIf { it.expiresAt > clock() }

    private fun loadFileLibrary(loaded: List<Message>) {
        fileLibrary.clear()
        for (record in libraryStore.load()) {
            if (fileStore.file(record.id) != null) {
                fileLibrary[record.id] = record
            }
        }
        for (message in loaded) {
            val meta = message.file ?: continue
            if (fileLibrary.containsKey(meta.id)) continue
            if (fileStore.file(meta.id) == null) continue
            val uploadedAt = message.ts
            fileLibrary[meta.id] = FileRecord(
                id = meta.id,
                name = meta.name,
                mime = meta.mime,
                size = meta.size,
                uploadedAt = uploadedAt,
                expiresAt = uploadedAt + fileRetentionMs,
                uploaderId = message.senderId,
                uploaderName = message.senderName,
            )
        }
        purgeExpiredLocked(clock())
        libraryStore.save(fileLibrary.values)
    }

    private fun purgeExpiredLocked(now: Long): Int {
        val expired = fileLibrary.values.filter { it.expiresAt <= now }
        if (expired.isEmpty()) return 0
        for (record in expired) {
            fileLibrary.remove(record.id)
            fileStore.delete(record.id)
        }
        libraryStore.save(fileLibrary.values)
        return expired.size
    }

    fun snapshot(limit: Int = 200): List<Message> = state.value.messages.takeLast(limit)

    fun messagesAfter(afterId: Long, limit: Int = 500): List<Message> =
        state.value.messages.filter { it.id > afterId }.takeLast(limit)

    fun findFileMessage(fileId: String): Message? =
        state.value.messages.firstOrNull { it.file?.id == fileId }

    fun file(fileId: String): File? = fileStore.file(fileId)

    fun publicUsers(): List<PublicUser> = users.values.map { it.publicView() }

    fun currentRoom(): RoomInfo = RoomInfo(
        name = roomName,
        epoch = meta.epoch,
        version = appVersion,
        maxFileBytes = maxFileBytes,
        startedAt = startedAt,
    )

    fun close() {
        started = false
    }

    private fun addMessage(message: Message) {
        messages.addLast(message)
        while (messages.size > MAX_MEMORY_MESSAGES) messages.removeFirst()
    }

    private fun publishState() {
        _state.value = RoomState(
            room = currentRoom(),
            users = publicUsers(),
            messages = messages.toList(),
            storageUsedBytes = fileStore.usedBytes(),
            storageFreeBytes = fileStore.freeBytes(),
        )
    }

    private fun nextSessionToken(): String = buildString(24) {
        repeat(24) {
            val index = random.nextInt(TOKEN_ALPHABET.length)
            append(TOKEN_ALPHABET[index])
        }
    }

    fun newToken(): String = nextSessionToken()

    private fun sanitizeClientId(id: String): String {
        val trimmed = id.trim().take(64)
        return if (trimmed.length >= 8 && trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            trimmed
        } else {
            UUID.randomUUID().toString()
        }
    }

    private fun sanitizeName(raw: String): String {
        val clean = raw.trim().filter { !it.isISOControl() }.take(MAX_NAME_CHARS)
        return clean.ifBlank { "访客" }
    }

    private fun sanitizeFileName(raw: String): String {
        val clean = raw.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .filter { !it.isISOControl() }
            .take(MAX_FILE_NAME_CHARS)
        return clean.ifBlank { "file" }
    }

    private fun sanitizeMime(raw: String): String {
        val clean = raw.substringBefore(';').trim().lowercase()
        return if (clean.matches(Regex("[a-z0-9.+_-]+/[a-z0-9.+_-]+"))) clean else "application/octet-stream"
    }

    companion object {
        private const val MAX_MEMORY_MESSAGES = 1000
        private const val MAX_TEXT_CHARS = 4000
        private const val MAX_NAME_CHARS = 20
        private const val MAX_FILE_NAME_CHARS = 120
        private const val TOKEN_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}
