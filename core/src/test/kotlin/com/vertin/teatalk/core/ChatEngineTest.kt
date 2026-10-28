package com.vertin.teatalk.core

import com.vertin.teatalk.core.engine.ChatEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val RETENTION_MS = 7L * DAY_MS

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

    private fun newEngine(
        clock: () -> Long = System::currentTimeMillis,
        fileRetentionMs: Long = RETENTION_MS,
    ): ChatEngine = ChatEngine(
        dataDir = File(tempDir, "data"),
        roomName = "测试房间",
        ioDispatcher = Dispatchers.IO,
        clock = clock,
        fileRetentionMs = fileRetentionMs,
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
    fun `file upload stores bytes metadata and a seven day library record`() = runTest {
        var now = 1_000_000L
        engine.close()
        engine = newEngine(clock = { now })
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

        val record = engine.listLibrary().single()
        assertEquals(message.file!!.id, record.id)
        assertEquals(now, record.uploadedAt)
        assertEquals(now + RETENTION_MS, record.expiresAt)
        assertEquals("柚子", record.uploaderName)
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
    fun `clear history rotates epoch but keeps files in the library`() = runTest {
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

        engine.clearHistory()

        assertNotEquals(oldEpoch, engine.currentRoom().epoch)
        assertTrue(engine.state.value.messages.isEmpty())
        assertEquals(1, engine.state.value.users.size)
        assertTrue(engine.file(message.file!!.id)!!.isFile)
        assertNotNull(engine.findFile(message.file!!.id))
        assertEquals(1, engine.listLibrary().size)
    }

    @Test
    fun `extend file adds seven days to its expiry`() = runTest {
        var now = 1_000_000L
        engine.close()
        engine = newEngine(clock = { now })
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
        val before = engine.listLibrary().single()
        assertEquals(now + RETENTION_MS, before.expiresAt)

        val extended = engine.extendFile(message.file!!.id)
        assertEquals(before.expiresAt + RETENTION_MS, extended?.expiresAt)
        assertEquals(before.expiresAt + RETENTION_MS, engine.listLibrary().single().expiresAt)
    }

    @Test
    fun `expired files are purged from disk and library`() = runTest {
        var now = 1_000_000L
        engine.close()
        engine = newEngine(clock = { now })
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
        val fileId = message.file!!.id

        now += RETENTION_MS + 1L
        assertEquals(1, engine.purgeExpiredFiles())
        assertTrue(engine.listLibrary().isEmpty())
        assertNull(engine.file(fileId))
        assertNull(engine.findFile(fileId))
    }

    @Test
    fun `file library survives restart`() = runTest {
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
        engine.close()

        engine = newEngine()
        engine.start()
        val record = engine.listLibrary().single()
        assertEquals(message.file!!.id, record.id)
        assertTrue(engine.file(record.id)!!.isFile)
    }

    @Test
    fun `legacy file messages seed the library when the index is missing`() = runTest {
        engine.start()
        val user = engine.join("client-12345678", "柚子", "token-1")
        val message = engine.addFile(
            id = user.id,
            declaredName = "legacy.txt",
            declaredMime = "text/plain",
            declaredSize = 1,
            input = ByteArrayInputStream(byteArrayOf(9)),
            clientMsgId = null,
        )
        engine.close()
        File(tempDir, "data/file-library.json").delete()

        engine = newEngine()
        engine.start()
        val record = engine.listLibrary().single()
        assertEquals(message.file!!.id, record.id)
        assertEquals("legacy.txt", record.name)
        assertNotNull(engine.findFile(record.id))
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
