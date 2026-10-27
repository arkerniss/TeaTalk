package com.vertin.teatalk.core.discovery

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryProtocolTest {
    @Test
    fun `room beacon round trips`() {
        val beacon = RoomBeacon(hostId = "host-1", room = "我的局域网", port = 8520)
        val parsed = DiscoveryProtocol.parse(
            DiscoveryProtocol.encodeRoom(beacon),
            InetAddress.getByName("192.168.1.23"),
        )

        val room = parsed as DiscoveryPacket.Room
        assertEquals("host-1", room.beacon.hostId)
        assertEquals("我的局域网", room.beacon.room)
        assertEquals(8520, room.beacon.port)
        assertEquals("192.168.1.23", room.sourceIp)
    }

    @Test
    fun `probe round trips`() {
        assertEquals(DiscoveryPacket.Probe, DiscoveryProtocol.parse(DiscoveryProtocol.encodeProbe()))
    }

    @Test
    fun `foreign app version and malformed packets are ignored`() {
        assertNull(DiscoveryProtocol.parse("""{"app":"other","v":1,"type":"probe"}""".toByteArray()))
        assertNull(DiscoveryProtocol.parse("""{"app":"teatalk","v":2,"type":"probe"}""".toByteArray()))
        assertNull(DiscoveryProtocol.parse("""{"app":"teatalk","v":1,"type":"room"}""".toByteArray()))
        assertNull(DiscoveryProtocol.parse("not-json".toByteArray()))
        assertNull(DiscoveryProtocol.parse(ByteArray(0)))
        assertNull(DiscoveryProtocol.parse(ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES + 1)))
    }

    @Test
    fun `invalid port and blank room are rejected`() {
        assertNull(
            DiscoveryProtocol.parse(
                """{"app":"teatalk","v":1,"type":"room","hostId":"h","room":"r","port":0}""".toByteArray(),
            ),
        )
        assertNull(
            DiscoveryProtocol.parse(
                """{"app":"teatalk","v":1,"type":"room","hostId":"h","room":"   ","port":8520}""".toByteArray(),
            ),
        )
    }

    @Test
    fun `room name is cleaned and truncated`() {
        val rawRoom = "a".repeat(80) + "\u0000"
        val payload = """{"app":"teatalk","v":1,"type":"room","hostId":"h","room":"$rawRoom","port":8520}"""
        val parsed = DiscoveryProtocol.parse(payload.toByteArray()) as DiscoveryPacket.Room
        assertEquals(DiscoveryProtocol.MAX_ROOM_NAME, parsed.beacon.room.length)
    }

    @Test
    fun `tracker dedupes by host id and expires stale rooms`() {
        var now = 1_000L
        val tracker = DiscoveredRoomTracker(staleAfterMs = 7_000L) { now }
        val beacon = RoomBeacon(hostId = "host-1", room = "A", port = 8520)
        tracker.update(beacon, "192.168.1.2")
        tracker.update(beacon.copy(room = "A2", port = 8521), "192.168.1.2")

        assertEquals(1, tracker.snapshot().size)
        assertEquals("A2", tracker.snapshot().first().room)
        assertEquals(8521, tracker.snapshot().first().port)

        now += 7_001L
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `tracker keeps newest rooms first`() {
        var now = 1_000L
        val tracker = DiscoveredRoomTracker(staleAfterMs = 7_000L) { now }
        tracker.update(RoomBeacon("host-a", "A", 8520), "192.168.1.2")
        now += 100L
        tracker.update(RoomBeacon("host-b", "B", 8520), "192.168.1.3")

        assertEquals(listOf("B", "A"), tracker.snapshot().map { it.room })
    }
}
