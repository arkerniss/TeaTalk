package com.vertin.teatalk.core.server

import com.google.gson.Gson
import com.vertin.teatalk.core.engine.ChatEngine
import com.vertin.teatalk.core.store.FileTooLargeException
import com.vertin.teatalk.core.store.UploadSizeMismatchException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val SESSION_HEADER = "X-TeaTalk-Session"
private const val CLIENT_MSG_ID_HEADER = "X-Client-Msg-Id"

fun Application.installRoutes(
    config: ServerConfig,
    engine: ChatEngine,
    hub: WsHub,
) {
    val gson = Gson()
    routing {
        get("/api/health") {
            call.addSecurityHeaders()
            call.respondText(
                gson.toJson(
                    mapOf(
                        "ok" to true,
                        "app" to config.appId,
                        "protocol" to 1,
                        "version" to config.appVersion,
                        "port" to config.port,
                        "users" to engine.publicUsers().size,
                        "room" to engine.currentRoom().name,
                    ),
                ),
                ContentType.Application.Json,
            )
        }

        webSocket("/ws") {
            hub.handle(call, this)
        }

        put("/api/upload") {
            call.addSecurityHeaders()
            val user = hub.userForToken(call.request.headers[SESSION_HEADER])
            if (user == null) {
                call.respondText(
                    """{"error":"session required"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return@put
            }
            val length = call.request.headers["Content-Length"]?.toLongOrNull()
            if (length == null || length < 0L) {
                call.respondText(
                    """{"error":"content-length required"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.LengthRequired,
                )
                return@put
            }
            if (length > engine.maxFileBytes) {
                call.respondText(
                    """{"error":"file too large"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
                return@put
            }
            val declaredName = call.request.queryParameters["name"] ?: "file"
            try {
                val message = engine.addFile(
                    id = user.id,
                    declaredName = declaredName,
                    declaredMime = call.request.contentType().toString(),
                    declaredSize = length,
                    input = call.receiveChannel().toInputStream(),
                    clientMsgId = call.request.headers[CLIENT_MSG_ID_HEADER],
                )
                call.respondText(
                    gson.toJson(mapOf("message" to message)),
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
            } catch (error: FileTooLargeException) {
                call.respondText(
                    """{"error":"file too large"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
            } catch (error: UploadSizeMismatchException) {
                call.respondText(
                    """{"error":"size mismatch"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            } catch (error: Throwable) {
                call.respondText(
                    """{"error":"upload failed"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }

        get("/api/files/{fileId}/{name?}") {
            call.addSecurityHeaders()
            val fileId = call.parameters["fileId"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val message = engine.findFileMessage(fileId)
            val file = engine.file(fileId)
            if (message == null || message.file == null || file == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.serveFile(file, message.file.name, message.file.size, message.file.mime)
        }

        get("/") {
            call.respondAsset(config, "index.html")
        }

        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
            call.respondAsset(config, path.ifBlank { "index.html" })
        }
    }
}

private suspend fun ApplicationCall.respondAsset(config: ServerConfig, path: String) {
    addSecurityHeaders()
    val safePath = path.removePrefix("/")
    val bytes = config.assetSource.read(safePath)
    if (bytes == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    response.headers.append("Cache-Control", "no-cache")
    if (safePath.endsWith(".html")) {
        response.headers.append(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' ws: wss:; " +
                "font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
        )
    }
    respondBytes(bytes, contentTypeFor(safePath), HttpStatusCode.OK)
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
    "html" -> ContentType.Text.Html
    "js" -> ContentType.Text.JavaScript
    "css" -> ContentType.Text.CSS
    "json", "webmanifest" -> ContentType.Application.Json
    "png" -> ContentType.Image.PNG
    "svg" -> ContentType.Image.SVG
    "ico" -> ContentType("image", "x-icon")
    "webp" -> ContentType("image", "webp")
    else -> ContentType.Application.OctetStream
}

private suspend fun ApplicationCall.serveFile(
    file: File,
    displayName: String,
    size: Long,
    declaredMime: String,
) {
    if (size <= 0L) {
        response.headers.append("Content-Disposition", attachment(displayName))
        respond(HttpStatusCode.OK)
        return
    }

    var start = 0L
    var end = size - 1L
    var partial = false
    val rangeHeader = request.headers["Range"]
    if (rangeHeader != null) {
        val parsed = parseSingleRange(rangeHeader, size)
        if (parsed == null) {
            response.headers.append("Content-Range", "bytes */$size")
            respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            return
        }
        start = parsed.first
        end = parsed.second
        partial = true
    }
    val length = end - start + 1L
    val imageType = safeImageContentType(displayName)
    val inline = imageType != null
    val contentType = imageType ?: ContentType.Application.OctetStream

    response.headers.append("Accept-Ranges", "bytes")
    response.headers.append("Cache-Control", "private, max-age=31536000, immutable")
    response.headers.append(
        "Content-Disposition",
        if (inline) inlineDisposition(displayName) else attachment(displayName),
    )
    if (partial) response.headers.append("Content-Range", "bytes $start-$end/$size")

    val content = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = contentType
        override val status: HttpStatusCode = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK
        override val contentLength: Long = length

        override suspend fun writeTo(channel: ByteWriteChannel) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    channel.writeFully(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
    respond(content)
}

private fun parseSingleRange(header: String, size: Long): Pair<Long, Long>? {
    if (!header.startsWith("bytes=")) return null
    val spec = header.removePrefix("bytes=").trim()
    if (spec.contains(',')) return null
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()
    val start: Long
    val end: Long
    if (startText.isEmpty()) {
        val suffix = endText.toLongOrNull() ?: return null
        if (suffix <= 0L) return null
        start = (size - suffix).coerceAtLeast(0L)
        end = size - 1L
    } else {
        start = startText.toLongOrNull() ?: return null
        end = if (endText.isEmpty()) size - 1L else (endText.toLongOrNull() ?: return null)
    }
    if (start < 0L || end < start || start >= size) return null
    return start to minOf(end, size - 1L)
}

private fun safeImageContentType(name: String): ContentType? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "png" -> ContentType.Image.PNG
    "gif" -> ContentType.Image.GIF
    "webp" -> ContentType("image", "webp")
    "avif" -> ContentType("image", "avif")
    "heic", "heif" -> ContentType("image", "heic")
    else -> null
}

private fun encodeFilename(name: String): String =
    URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")

private fun attachment(name: String): String =
    "attachment; filename=\"${name.replace("\"", "_")}\"; filename*=UTF-8''${encodeFilename(name)}"

private fun inlineDisposition(name: String): String =
    "inline; filename=\"${name.replace("\"", "_")}\"; filename*=UTF-8''${encodeFilename(name)}"

private fun ApplicationCall.addSecurityHeaders() {
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Frame-Options", "DENY")
}
