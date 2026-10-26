package com.vertin.toutouliao.core.model

import com.google.gson.annotations.SerializedName

enum class MessageKind {
    @SerializedName("text")
    TEXT,

    @SerializedName("file")
    FILE,

    @SerializedName("system")
    SYSTEM,
}

data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
)

data class Message(
    val id: Long,
    val kind: MessageKind,
    val senderId: String? = null,
    val senderName: String? = null,
    val text: String? = null,
    val file: FileMeta? = null,
    val ts: Long,
    val clientMsgId: String? = null,
)

data class User(
    val id: String,
    val name: String,
    val joinedAt: Long,
    val sessionToken: String,
) {
    fun publicView(): PublicUser = PublicUser(id = id, name = name, joinedAt = joinedAt)
}

data class PublicUser(
    val id: String,
    val name: String,
    val joinedAt: Long,
)

data class RoomInfo(
    val name: String,
    val epoch: String,
    val version: String,
    val maxFileBytes: Long,
    val startedAt: Long,
)

data class RoomState(
    val room: RoomInfo,
    val users: List<PublicUser> = emptyList(),
    val messages: List<Message> = emptyList(),
    val storageUsedBytes: Long = 0L,
    val storageFreeBytes: Long = 0L,
)

sealed interface RoomEvent {
    data class MessageAdded(val message: Message) : RoomEvent
    data class UsersChanged(val users: List<PublicUser>) : RoomEvent
    data class RoomChanged(val room: RoomInfo) : RoomEvent
    data class Cleared(val room: RoomInfo) : RoomEvent
}
