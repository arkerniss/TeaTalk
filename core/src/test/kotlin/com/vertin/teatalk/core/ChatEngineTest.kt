package com.vertin.teatalk.core

import com.vertin.teatalk.core.engine.ChatEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ChatEngineTest {
    private lateinit var tempDir: File
    private lateinit var engine: ChatEngine

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("teatalk-test").toFile()
        engine = newEngine()
    }

    @After
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    private fun newEngine(): ChatEngine = ChatEngine(
        dataDir = File(tempDir, "data"),
        roomName = "测试房间",
        ioDispatcher = Dispatchers.IO,
    )

    @Test
    fun `text message is added to state and survives restart`() = runTest {
        engine.start()
        val user = engine.join("client-12345678", "柚子", "token-1")
        val message = engine.sendText(user.id, " 你好 ", null)

        assertEquals("你好", message.text)
        assertEquals(1, engine.state.value.messages.size)
        assertEquals("柚子", message.senderName)
        engine.close()

        engine = newEngine()
        engine.start()
        assertEquals(1, engine.state.value.messages.size)
        assertEquals("你好", engine.state.value.messages.first().text)
    }

    @Test
    fun `file upload stores bytes and metadata`() = runTest {
        engine.start()
        val user = engine.join("client-12345678", "柚子", "token-1")
        val message = engine.addFile(
            id = user.id,
            declaredName = "照片.jpg",
            declaredMime = "image/jpeg",
            declaredSize = 3,
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            clientMsgId = "msg-1",
        )

        assertEquals(3L, message.file?.size)
        assertEquals("照片.jpg", message.file?.name)
        assertEquals("image/jpeg", message.file?.mime)
        assertTrue(engine.file(message.file!!.id)!!.isFile)
        assertEquals(3L, engine.file(message.file!!.id)!!.length())
    }

    @Test
    fun `replaced session does not delete the new user`() = runTest {
        engine.start()
        val first = engine.join("client-12345678", "旧名字", "token-1")
        engine.join(first.id, "新名字", "token-2")
        assertEquals(1, engine.state.value.users.size)

        engine.leave(first.id, "token-1")
        assertEquals(1, engine.state.value.users.size)

        engine.leave(first.id, "token-2")
        assertEquals(0, engine.state.value.users.size)
    }

    @Test
    fun `clear rotates epoch and removes history and files`() = runTest {
        engine.start()
        val user = engine.join("client-12345678", "柚子", "token-1")
        val message = engine.addFile(
            id = user.id,
            declaredName = "a.txt",
            declaredMime = "text/plain",
            declaredSize = 1,
            input = ByteArrayInputStream(byteArrayOf(7)),
            clientMsgId = null,
        )
        val oldEpoch = engine.currentRoom().epoch

        engine.clear()

        assertNotEquals(oldEpoch, engine.currentRoom().epoch)
        assertTrue(engine.state.value.messages.isEmpty())
        assertNull(engine.file(message.file!!.id))
        assertEquals(1, engine.state.value.users.size)
    }

    @Test
    fun `messages after a cursor are returned in order`() = runTest {
        engine.start()
        val user = engine.join("client-12345678", "柚子", "token-1")
        val first = engine.sendText(user.id, "一", null)
        val second = engine.sendText(user.id, "二", null)

        val after = engine.messagesAfter(first.id)
        assertEquals(1, after.size)
        assertEquals(second.id, after.first().id)
    }
}
