package io.github.magikdidi24.budspopup.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParserTest {
    private fun fixture(name: String): ByteArray =
        javaClass.getResource("/frames/$name.hex")!!.readText().trim().split(Regex("\\s+"))
            .map { it.toInt(16).toByte() }.toByteArray()

    private fun single(bytes: ByteArray) = SppFrameSplitter().feed(bytes).single()

    @Test
    fun `extended status from a real Buds4 Pro`() {
        // Capture from GalaxyBudsClient's test data (Buds4Pro_rev4): worn, black buds.
        val frame = single(fixture("buds4pro_extended_status_wearing"))
        assertEquals(StatusParser.EXTENDED_STATUS_UPDATED, frame.id)
        assertEquals(62, frame.payload.size)
        val s = StatusParser.parse(frame)!!
        assertEquals(70, s.left)
        assertEquals(89, s.right)
        assertNull(s.case)
        assertEquals(Placement.Wearing, s.placementLeft)
        assertEquals(Placement.Wearing, s.placementRight)
        assertEquals(BudsColor.Black, s.color)
        assertFalse(s.anyInCase)
        assertTrue(s.anyWorn)
    }

    @Test
    fun `status update with both buds in a charging case`() {
        // Synthetic frame (layout of the Buds4 Pro STATUS_UPDATED), CRC computed by hand.
        val s = StatusParser.parse(single(fixture("buds4pro_status_in_case")))!!
        assertEquals(BudsStatus(85, 80, 62, Placement.InCase, Placement.InCase, caseCharging = true), s)
        assertTrue(s.anyInCase)
    }

    @Test
    fun `splitter handles garbage, split reads and back-to-back frames`() {
        val a = fixture("buds4pro_extended_status_wearing")
        val b = fixture("buds4pro_status_in_case")
        val stream = byteArrayOf(0x00, 0x12, 0xFD.toByte()) + a + b
        val splitter = SppFrameSplitter()
        val frames = listOf(stream.copyOfRange(0, 10), stream.copyOfRange(10, 70), stream.copyOfRange(70, stream.size))
            .flatMap { splitter.feed(it) }
        assertEquals(listOf(0x61, 0x60), frames.map { it.id })
    }

    @Test
    fun `corrupted crc is dropped`() {
        val bad = fixture("buds4pro_status_in_case").also { it[6] = 0x01 }
        assertTrue(SppFrameSplitter().feed(bad).isEmpty())
    }

    @Test
    fun `other messages are ignored`() {
        assertNull(StatusParser.parse(SppFrame(0x77, ByteArray(10))))
    }
}
