package io.github.magikdidi24.budspopup.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyFrameTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `init request is encoded as Gadgetbridge documents it`() {
        // type 0x0c, sequence 0, length 2, payload 00 00, checksum 0x0e
        assertArrayEquals(hex("3e 0c 00 00 00 00 02 00 00 0e 3c"), SonyFrame(SonyFrame.COMMAND, 0, HeadphonesStatus.INIT_REQUEST).encode())
    }

    @Test
    fun `reserved bytes are escaped and come back unchanged`() {
        val payload = hex("11 00 3c 3d 3e")
        val encoded = SonyFrame(SonyFrame.COMMAND, 1, payload).encode()
        assertTrue(encoded.drop(1).dropLast(1).none { it == 0x3C.toByte() || it == 0x3E.toByte() })
        val back = SonyFrameSplitter().feed(encoded).single()
        assertArrayEquals(payload, back.payload)
        assertEquals(1, back.sequence)
    }

    @Test
    fun `battery reply split over two reads, after an ack`() {
        val ack = SonyFrame(SonyFrame.ACK, 1, ByteArray(0)).encode()
        val reply = SonyFrame(SonyFrame.COMMAND, 0, hex("11 00 46 01")).encode()
        val stream = ack + reply
        val splitter = SonyFrameSplitter()
        val frames = splitter.feed(stream.copyOfRange(0, 9)) + splitter.feed(stream.copyOfRange(9, stream.size))
        assertEquals(listOf(SonyFrame.ACK, SonyFrame.COMMAND), frames.map { it.type })
        assertEquals(HeadphonesStatus(70, true), HeadphonesStatus.parse(frames[1]))
    }

    @Test
    fun `notifications count, other messages and bad checksums do not`() {
        val notify = SonyFrame(SonyFrame.COMMAND, 0, hex("13 00 20 00"))
        assertEquals(HeadphonesStatus(32, false), HeadphonesStatus.parse(notify))
        assertNull(HeadphonesStatus.parse(SonyFrame(SonyFrame.COMMAND, 0, hex("01 00 40 10"))))
        assertTrue(HeadphonesStatus.isInitReply(SonyFrame(SonyFrame.COMMAND, 0, hex("01 00 40 10"))))
        val broken = SonyFrame(SonyFrame.COMMAND, 0, hex("11 00 46 01")).encode().also { it[8] = 0x47 }
        assertTrue(SonyFrameSplitter().feed(broken).isEmpty())
    }
}
