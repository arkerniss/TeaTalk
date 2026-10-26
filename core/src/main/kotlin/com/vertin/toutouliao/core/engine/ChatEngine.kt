package com.vertin.toutouliao.core.engine

import com.google.gson.Gson
import com.vertin.toutouliao.core.model.FileMeta
import com.vertin.toutouliao.core.model.Message
import com.vertin.toutouliao.core.model.MessageKind
import com.vertin.toutouliao.core.model.PublicUser
import com.vertin.toutouliao.core.model.RoomEvent
import com.vertin.toutouliao.core.model.RoomInfo
import com.vertin.toutouliao.core.model.RoomState
import com.vertin.toutouliao.core.model.User
import com.vertin.toutouliao.core.store.FileStore
import com.vertin.toutouliao.core.store.MessageLog
import com.vertin.toutouliao.core.store.RoomMeta
import com.vertin.toutouliao.core.store.RoomMetaStore
import com.vertin.toutouliao.core.store.UploadSizeMismatchException
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gson = Gson()
    private val metaStore = RoomMetaStore(File(dataDir, "meta.json"), gson)
    private val log = MessageLog(File(dataDir, "messages.jsonl"), gson)
    private val fileStore = FileStore(dataDir)
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
        val actualSize = fileStore.save(fileId, input, maxFileBytes, ioDispatcher)
        if (declaredSize >= 0L && actualSize != declaredSize) {
            fileStore.delete(fileId)
            throw UploadSizeMismatchException(declaredSize, actualSize)
        }
        return withContext(ioDispatcher) {
            mutex.withLock {
                val message = Message(
                    id = nextId.getAndIncrement(),
                    kind = MessageKind.FILE,
                    senderId = user.id,
                    senderName = user.name,
                    file = FileMeta(
                        id = fileId,
                        name = sanitizeFileName(declaredName),
                        size = actualSize,
                        mime = sanitizeMime(declaredMime),
                    ),
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
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            meta = metaStore.rotate(clock())
            log.clear()
            fileStore.clear()
            messages.clear()
            publishState()
            _events.tryEmit(RoomEvent.Cleared(currentRoom()))
        }
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
