package com.vertin.teatalk.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadState { RUNNING, DONE, FAILED, CANCELED }

data class DownloadTask(
    val id: Long,
    val name: String,
    val mime: String?,
    val url: String,
    /** -1 when the server sent no usable Content-Length. */
    val totalBytes: Long,
    val bytesRead: Long,
    val state: DownloadState,
    /** Human-readable location, set once DONE ("下载/茶室/x.png" on Q+, absolute path below). */
    val savedTo: String?,
    /** Non-null on API 29+ so the UI can offer 打开 via ACTION_VIEW. */
    val contentUri: Uri?,
    val error: String?,
)

/**
 * In-app downloader: streams http(s) files itself instead of handing them to the
 * system download service, and reports progress through [tasks] so the chat screen
 * can show a sheet over the WebView.
 *
 * Queue: strictly one transfer at a time. [enqueue] returns immediately and puts the
 * id on an unlimited channel; a single worker coroutine (owned by [scope]) drains it.
 * A task canceled while still queued is simply skipped by the worker.
 *
 * Cancel: the task is marked CANCELED first (so the UI reacts immediately), then the
 * live [HttpURLConnection] is disconnected, which unblocks the socket read and lets
 * the worker delete the pending MediaStore row / `.part` file. Nothing half-written
 * is ever visible: Q+ rows carry IS_PENDING=1 until success, and pre-Q writes go to a
 * cache `.part` file that is only copied into the app's Download dir at the end.
 */
class InAppDownloader(private val context: Context, private val scope: CoroutineScope) {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val lock = Any()
    private val nextId = AtomicLong(0L)
    private val queue = Channel<Long>(Channel.UNLIMITED)

    /** Id of the transfer the worker is busy with, or -1. Written on IO, read by [cancel]. */
    @Volatile
    private var activeId: Long = -1L

    /** Live connection, kept only so [cancel]/[close] can disconnect it promptly. */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private val worker: Job = scope.launch {
        // A previous process may have died mid-download; stale .part files are dead
        // weight. Purge before draining the queue so a fresh transfer cannot race it.
        withContext(Dispatchers.IO) {
            runCatching { tempDir().listFiles()?.forEach { it.delete() } }
        }
        for (id in queue) runQueued(id)
    }

    /** Returns the new task id; the transfer starts as soon as the worker is free. */
    fun enqueue(url: String, name: String, mime: String?): Long {
        val id = nextId.incrementAndGet()
        val task = DownloadTask(
            id = id,
            name = sanitiseFileName(name),
            mime = mime?.takeIf { it.isNotBlank() },
            url = url,
            totalBytes = -1L,
            bytesRead = 0L,
            state = DownloadState.RUNNING,
            savedTo = null,
            contentUri = null,
            error = null,
        )
        synchronized(lock) { _tasks.value = _tasks.value + task }
        if (queue.trySend(id).isFailure) publish(id) { it.copy(state = DownloadState.CANCELED) }
        return id
    }

    fun cancel(id: Long) {
        val task = snapshot(id) ?: return
        if (task.state != DownloadState.RUNNING) return
        publish(id) { it.copy(state = DownloadState.CANCELED, error = null) }
        if (activeId == id) activeConnection?.disconnect()
    }

    /** Removes a finished entry from the list (ignored while the transfer is running). */
    fun dismiss(taskId: Long) {
        synchronized(lock) {
            val current = _tasks.value
            val task = current.firstOrNull { it.id == taskId } ?: return
            if (task.state == DownloadState.RUNNING) return
            _tasks.value = current.filterNot { it.id == taskId }
        }
    }

    fun dismissFinished() {
        synchronized(lock) {
            _tasks.value = _tasks.value.filter { it.state == DownloadState.RUNNING }
        }
    }

    /**
     * Aborts the live transfer and stops the worker for good. The shared instance is
     * process-wide and is deliberately never closed by an Activity; this exists for tests
     * and for a future explicit "cancel everything" action.
     */
    fun close() {
        queue.close()
        synchronized(lock) {
            _tasks.value = _tasks.value.map {
                if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.CANCELED) else it
            }
        }
        activeConnection?.disconnect()
        worker.cancel()
    }

    // ── worker ────────────────────────────────────────────────────────────────

    private suspend fun runQueued(id: Long) {
        val task = snapshot(id) ?: return
        // Canceled while waiting in the queue: never touched the network.
        if (task.state != DownloadState.RUNNING) return
        activeId = id
        try {
            withContext(Dispatchers.IO) { transfer(id, task) }
        } finally {
            if (activeId == id) activeId = -1L
            activeConnection = null
        }
    }

    private fun transfer(id: Long, task: DownloadTask) {
        var connection: HttpURLConnection? = null
        var sink: OutputStream? = null
        var pendingUri: Uri? = null
        var tempFile: File? = null
        var committed = false
        try {
            if (isCanceled(id)) return
            val mime = resolveMime(task)
            if (mime != task.mime) publish(id) { it.copy(mime = mime) }

            val protocol = task.url.substringBefore(':', "").lowercase()
            if (protocol != "http" && protocol != "https") throw DownloadException("不支持的文件地址")
            val conn = (URL(task.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
            }
            connection = conn
            activeConnection = conn
            if (isCanceled(id)) return

            val code = conn.responseCode
            if (code !in 200..299) throw DownloadHttpException(code)
            val declared = conn.contentLengthLong
            publish(id) { it.copy(totalBytes = if (declared > 0L) declared else -1L) }

            // Q+: the pending row hides the partial file until IS_PENDING is cleared.
            // Pre-Q: stream into a cache .part file, then move it into the app's Download dir.
            val out: OutputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = insertPending(task, mime)
                pendingUri = uri
                context.contentResolver.openOutputStream(uri)
                    ?: throw DownloadException("无法写入下载目录")
            } else {
                val part = File(tempDir(), "$id.part")
                tempFile = part
                FileOutputStream(part)
            }
            sink = out

            val bytes = conn.inputStream.use { input -> copyStream(id, input, out) }
            out.flush()
            out.close()
            sink = null

            if (pendingUri != null) {
                val uri = pendingUri
                finishPending(uri)
                pendingUri = null
                committed = true
                // Resolve the display name before publishing: publish holds a lock and
                // must never run a blocking ContentResolver query inside it.
                val savedName = displayNameOf(uri) ?: task.name
                publish(id) {
                    it.copy(
                        state = DownloadState.DONE,
                        bytesRead = bytes,
                        totalBytes = if (it.totalBytes > 0L) it.totalBytes else bytes,
                        savedTo = "下载/茶室/$savedName",
                        contentUri = uri,
                        error = null,
                    )
                }
            } else {
                val source = tempFile ?: throw DownloadException("下载文件丢失")
                val target = commitLegacyFile(task, source)
                tempFile = null
                committed = true
                publish(id) {
                    it.copy(
                        state = DownloadState.DONE,
                        bytesRead = bytes,
                        totalBytes = if (it.totalBytes > 0L) it.totalBytes else bytes,
                        savedTo = target.absolutePath,
                        contentUri = null,
                        error = null,
                    )
                }
            }
        } catch (e: Exception) {
            if (isCanceled(id) || e is DownloadCanceledException) {
                publish(id) { it.copy(state = DownloadState.CANCELED, error = null) }
            } else {
                publish(id) { it.copy(state = DownloadState.FAILED, error = readableError(e)) }
            }
        } finally {
            if (activeConnection === connection) activeConnection = null
            runCatching { sink?.close() }
            if (!committed) {
                pendingUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
                runCatching { tempFile?.delete() }
            }
            runCatching { connection?.disconnect() }
        }
    }

    /** Copies the response body, publishing throttled progress. Returns the byte count. */
    private fun copyStream(id: Long, input: InputStream, sink: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var lastPublish = 0L
        while (true) {
            if (isCanceled(id)) throw DownloadCanceledException()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            sink.write(buffer, 0, read)
            total += read
            val now = SystemClock.uptimeMillis()
            // 8 KB chunks would thrash the composition; 120 ms is roughly one frame burst.
            if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
                lastPublish = now
                publish(id) { it.copy(bytesRead = total) }
            }
        }
        return total
    }

    // ── storage ───────────────────────────────────────────────────────────────

    private fun insertPending(task: DownloadTask, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, task.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw DownloadException("无法创建下载文件")
    }

    private fun finishPending(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (context.contentResolver.update(uri, values, null, null) <= 0) {
            throw DownloadException("无法保存到下载目录")
        }
    }

    /** MediaStore may adjust the display name (e.g. "茶桌 (1).png"); read the real one back. */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun commitLegacyFile(task: DownloadTask, source: File): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw DownloadException("外部存储不可用")
        if (!dir.exists() && !dir.mkdirs()) throw DownloadException("无法创建下载目录")
        val target = uniqueFile(dir, task.name)
        try {
            source.copyTo(target, overwrite = false)
        } catch (e: Exception) {
            // A half-copied file must not survive next to the real downloads.
            runCatching { target.delete() }
            throw e
        }
        source.delete()
        return target
    }

    private fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var index = 2
        while (true) {
            val next = File(dir, "$base ($index)$suffix")
            if (!next.exists()) return next
            index++
        }
    }

    private fun tempDir(): File = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }

    // ── task list plumbing ────────────────────────────────────────────────────

    private fun publish(id: Long, transform: (DownloadTask) -> DownloadTask) {
        synchronized(lock) {
            val current = _tasks.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return
            val next = transform(current[index])
            if (next == current[index]) return
            _tasks.value = current.toMutableList().also { it[index] = next }
        }
    }

    private fun snapshot(id: Long): DownloadTask? = synchronized(lock) {
        _tasks.value.firstOrNull { it.id == id }
    }

    private fun isCanceled(id: Long): Boolean {
        val task = snapshot(id) ?: return true
        return task.state != DownloadState.RUNNING
    }

    private fun resolveMime(task: DownloadTask): String {
        val provided = task.mime?.takeIf {
            it.isNotBlank() && !it.equals("application/octet-stream", ignoreCase = true)
        }
        if (provided != null) return provided
        val extension = task.name.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        return "application/octet-stream"
    }

    /** Every branch here is display-ready Chinese; raw socket messages stay in the log. */
    private fun readableError(e: Exception): String = when (e) {
        is DownloadCanceledException -> "下载已取消"
        is DownloadHttpException -> "服务器返回 HTTP ${e.code}"
        is DownloadException -> e.message ?: "下载失败"
        is SocketTimeoutException -> "连接超时，请重试"
        is UnknownHostException -> "找不到服务器"
        is ConnectException -> "连接不上服务器"
        is IOException -> "网络连接中断，请重试"
        else -> "下载失败"
    }

    /** A planned, user-readable failure (storage, protocol, HTTP status). */
    private open class DownloadException(message: String) : IOException(message)

    private class DownloadHttpException(val code: Int) : DownloadException("HTTP $code")

    /** Internal control-flow exception: the user canceled, not a transport failure. */
    private class DownloadCanceledException : DownloadException("下载已取消")

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 120L
        private const val TEMP_DIR = "download-tmp"
        private const val DOWNLOAD_SUBDIR = "茶室"

        /**
         * Process-wide instance, like [com.vertin.teatalk.host.HostRuntime].
         *
         * The chat screen is a WebView activity the user leaves as soon as a file starts
         * downloading; an activity-scoped downloader would abort the transfer in
         * `onDestroy`. Owning the worker from an application-level scope keeps the
         * transfer alive, and re-entering the chat screen picks the progress back up from
         * the same [tasks] flow.
         */
        @Volatile
        private var instance: InAppDownloader? = null

        fun get(context: Context): InAppDownloader = instance ?: synchronized(this) {
            instance ?: InAppDownloader(
                context.applicationContext,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
    }
}

/**
 * Best-effort filename for a download: RFC 5987 `filename*=UTF-8''…` first, then a
 * quoted `filename="…"`, then the URL's last path segment. The result is always safe
 * to hand to MediaStore or the filesystem.
 */
fun guessDownloadFileName(url: String, contentDisposition: String?): String {
    val fromHeader = contentDisposition?.let { header ->
        Regex("filename\\*=UTF-8''([^;]+)").find(header)?.groupValues?.getOrNull(1)
            ?.let { encoded ->
                runCatching {
                    URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
                }.getOrNull()
            }
            ?: Regex("filename=\"?([^\";]+)\"?").find(header)?.groupValues?.getOrNull(1)
    }
    val fallback = Uri.parse(url).lastPathSegment
    return sanitiseFileName(fromHeader ?: fallback ?: "download")
}

/** Strips path/control characters, trims to 120 chars, and never returns a blank name. */
private fun sanitiseFileName(raw: String): String =
    raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
        .trim()
        .take(120)
        .ifBlank { "download" }
