package com.vertin.toutouliao.core.server

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.vertin.toutouliao.core.engine.ChatEngine
import com.vertin.toutouliao.core.model.RoomEvent
import com.vertin.toutouliao.core.model.User
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * WebSocket hub. One session per browser/WebView tab. A session token is also
 * used by the raw upload endpoint so uploads are attributable to a user.
 */
class WsHub(private val engine: ChatEngine) {
    private val gson = Gson()
    private val byToken = ConcurrentHashMap<String, WsSession>()
    private val sessions = CopyOnWriteArraySet<WsSession>()

    fun userForToken(token: String?): User? = token?.let { byToken[it]?.user }

    fun broadcast(event: RoomEvent) {
        val json = eventJson(event) ?: return
        for (session in sessions) {
            // Slow clients are dropped on overflow and resync after reconnect.
            session.outbound.trySend(json)
        }
    }

    suspend fun handle(call: ApplicationCall, ws: DefaultWebSocketServerSession) {
        val origin = call.request.headers[HttpHeaders.Origin]?.trimEnd('/')
        val host = call.request.headers[HttpHeaders.Host]
        if (origin == null || host == null || origin != "http://$host") {
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin not allowed"))
            return
        }

        val firstFrame = ws.incoming.receiveCatching().getOrNull() as? Frame.Text ?: run {
            ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "hello required"))
            return
        }
        val hello = runCatching {
            JsonParser.parseString(firstFrame.readText()).asJsonObject
        }.getOrNull() ?: run {
            ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "bad hello"))
            return
        }

        val token = engine.newToken()
        val user = engine.join(
            id = hello.get("clientId")?.asString ?: "",
            name = hello.get("name")?.asString ?: "访客",
            token = token,
        )
        val afterId = hello.get("after")?.asLong ?: 0L
        val session = WsSession(token = token, user = user, outbound = Channel(256))
        byToken[token] = session
        sessions.add(session)

        val messages = if (afterId > 0L) engine.messagesAfter(afterId) else engine.snapshot(200)
        val snapshot = mapOf(
            "v" to 1,
            "t" to "snapshot",
            "self" to mapOf(
                "id" to user.id,
                "name" to user.name,
                "token" to token,
            ),
            "room" to engine.currentRoom(),
            "users" to engine.publicUsers(),
            "messages" to messages,
        )
        ws.send(Frame.Text(gson.toJson(snapshot)))

        val writer = CoroutineScope(currentCoroutineContext()).launch {
            for (text in session.outbound) {
                ws.send(Frame.Text(text))
            }
        }

        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                handleClientFrame(user, frame.readText(), ws)
            }
        } finally {
            session.outbound.close()
            writer.cancel()
            sessions.remove(session)
            byToken.remove(token)
            engine.leave(user.id, token)
        }
    }

    private suspend fun handleClientFrame(user: User, text: String, ws: DefaultWebSocketServerSession) {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (obj.get("t")?.asString) {
            "text" -> {
                runCatching {
                    engine.sendText(
                        id = user.id,
                        rawText = obj.get("text")?.asString ?: "",
                        clientMsgId = obj.get("clientMsgId")?.asString,
                    )
                }.onFailure {
                    sendError(ws, "bad_request", it.message ?: "发送失败")
                }
            }

            "rename" -> {
                runCatching { engine.rename(user.id, obj.get("name")?.asString ?: "") }
            }

            "ping" -> ws.send(Frame.Text("""{"t":"pong"}"""))
        }
    }

    private suspend fun sendError(ws: DefaultWebSocketServerSession, code: String, message: String) {
        ws.send(Frame.Text(gson.toJson(mapOf("t" to "error", "code" to code, "message" to message))))
    }

    private fun eventJson(event: RoomEvent): String? = when (event) {
        is RoomEvent.MessageAdded -> gson.toJson(mapOf("t" to "message", "message" to event.message))
        is RoomEvent.UsersChanged -> gson.toJson(mapOf("t" to "users", "users" to event.users))
        is RoomEvent.RoomChanged -> gson.toJson(mapOf("t" to "room", "room" to event.room))
        is RoomEvent.Cleared -> gson.toJson(
            mapOf(
                "t" to "cleared",
                "epoch" to event.room.epoch,
                "room" to event.room,
            ),
        )
    }

    private class WsSession(
        val token: String,
        val user: User,
        val outbound: Channel<String>,
    )
}
