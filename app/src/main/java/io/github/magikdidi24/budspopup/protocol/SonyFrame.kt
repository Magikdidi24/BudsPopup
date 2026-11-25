package io.github.magikdidi24.budspopup.protocol

/**
 * One message of the Sony headphones protocol (WH-1000XM4, "v1"):
 * `0x3E | escaped(type, sequence, payload length (4 bytes, big endian), payload, checksum) | 0x3C`.
 * The checksum is the byte sum of what precedes it; 0x3C, 0x3D and 0x3E inside are written as
 * 0x3D followed by the byte with bit 4 cleared. Layout taken from Gadgetbridge's documentation.
 */
class SonyFrame(val type: Int, val sequence: Int, val payload: ByteArray) {
    fun encode(): ByteArray {
        val n = payload.size
        val body = byteArrayOf(type.toByte(), sequence.toByte(), (n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + payload
        val escaped = (body + checksum(body, body.size)).flatMap { b ->
            if (b == START || b == END || b == ESCAPE) listOf(ESCAPE, (b.toInt() and 0xEF).toByte()) else listOf(b)
        }
        return byteArrayOf(START) + escaped.toByteArray() + byteArrayOf(END)
    }

    companion object {
        const val ACK = 0x01
        const val COMMAND = 0x0C
        private const val START: Byte = 0x3E
        private const val END: Byte = 0x3C
        private const val ESCAPE: Byte = 0x3D

        private fun checksum(bytes: ByteArray, end: Int) = (0 until end).sumOf { bytes[it].toInt() and 0xFF }.toByte()

        /** The bytes between START and END, still escaped; null when the checksum or length is wrong. */
        internal fun decode(escaped: List<Byte>): SonyFrame? {
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i < escaped.size) {
                if (escaped[i] == ESCAPE && i + 1 < escaped.size) bytes += (escaped[++i].toInt() or 0x10).toByte() else bytes += escaped[i]
                i++
            }
            val b = bytes.toByteArray()
            if (b.size < 7 || checksum(b, b.size - 1) != b.last()) return null
            val length = ((b[2].toInt() and 0xFF) shl 24) or ((b[3].toInt() and 0xFF) shl 16) or
                ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
            if (length != b.size - 7) return null
            return SonyFrame(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b.copyOfRange(6, 6 + length))
        }
    }
}

/** Cuts a byte stream into frames; START and END never appear escaped inside one. */
class SonyFrameSplitter {
    private val buf = mutableListOf<Byte>()
    private var inside = false

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<SonyFrame> {
        val frames = mutableListOf<SonyFrame>()
        for (i in 0 until count) {
            when (val b = bytes[i]) {
                0x3E.toByte() -> { buf.clear(); inside = true }
                0x3C.toByte() -> if (inside) { SonyFrame.decode(buf)?.let(frames::add); inside = false }
                else -> if (inside) buf += b
            }
        }
        return frames
    }
}

data class HeadphonesStatus(val level: Int, val charging: Boolean) {
    companion object {
        private const val INIT_REPLY = 0x01
        private const val BATTERY_REPLY = 0x11
        private const val BATTERY_NOTIFY = 0x13
        private const val SINGLE_BATTERY = 0x00

        /** Asks for the level of a single battery (over-ear headphones have one). */
        val BATTERY_REQUEST = byteArrayOf(0x10, SINGLE_BATTERY.toByte())
        val INIT_REQUEST = byteArrayOf(0x00, 0x00)

        fun isInitReply(frame: SonyFrame) = frame.type == SonyFrame.COMMAND && frame.payload.firstOrNull()?.toInt() == INIT_REPLY

        fun parse(frame: SonyFrame): HeadphonesStatus? {
            val p = frame.payload
            if (frame.type != SonyFrame.COMMAND || p.size < 4) return null
            if (p[0].toInt() != BATTERY_REPLY && p[0].toInt() != BATTERY_NOTIFY) return null
            if (p[1].toInt() != SINGLE_BATTERY) return null
            return HeadphonesStatus(p[2].toInt() and 0xFF, p[3].toInt() == 1)
        }
    }
}
