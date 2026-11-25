package io.github.magikdidi24.budspopup.protocol

/**
 * One Galaxy Buds SPP message (Buds+ and newer, SOM 0xFD / EOM 0xDD):
 * `SOM | header (2 bytes LE) | id | payload | CRC16 (LE) | EOM`.
 * The header holds the size of id + payload + CRC in its low 10 bits,
 * bit 12 = request/response, bit 13 = fragment.
 */
class SppFrame(val id: Int, val payload: ByteArray, val isFragment: Boolean = false) {
    override fun toString() = "SppFrame(id=0x%02x, ${payload.size} bytes)".format(id)

    companion object {
        const val SOM = 0xFD
        const val EOM = 0xDD
        private const val OVERHEAD = 4 // SOM + header + EOM; size field covers the rest

        /** CRC-16/XMODEM (poly 0x1021, init 0) over the message id and payload. */
        fun crc16(data: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            }
            return crc and 0xFFFF
        }

        /** Length of the frame at [start] if a complete, valid one is there; 0 if more bytes are needed; -1 if invalid. */
        internal fun frameLength(buf: ByteArray, start: Int, end: Int): Int {
            if (end - start < OVERHEAD) return 0
            val size = ((buf[start + 1].toInt() and 0xFF) or ((buf[start + 2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (size < 3) return -1
            val total = size + OVERHEAD
            if (end - start < total) return 0
            if (buf[start + total - 1].toInt() and 0xFF != EOM) return -1
            val crcAt = start + total - 3
            val crc = (buf[crcAt].toInt() and 0xFF) or ((buf[crcAt + 1].toInt() and 0xFF) shl 8)
            return if (crc16(buf, start + 3, crcAt) == crc) total else -1
        }
    }
}

/**
 * Cuts an RFCOMM byte stream into frames. A read can hold several frames or half of one,
 * so bytes are buffered until a full frame is there; garbage before a SOM is skipped.
 */
class SppFrameSplitter {
    private var buf = ByteArray(0)

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<SppFrame> {
        buf += bytes.copyOf(count)
        val frames = mutableListOf<SppFrame>()
        var pos = 0
        while (pos < buf.size) {
            if (buf[pos].toInt() and 0xFF != SppFrame.SOM) { pos++; continue }
            val len = SppFrame.frameLength(buf, pos, buf.size)
            if (len == 0) {
                // Waiting for the rest, unless this SOM was a stray byte: then a complete frame follows.
                pos = (pos + 1 until buf.size).firstOrNull {
                    buf[it].toInt() and 0xFF == SppFrame.SOM && SppFrame.frameLength(buf, it, buf.size) > 0
                } ?: break
                continue
            }
            if (len < 0) { pos++; continue }
            val header = (buf[pos + 2].toInt() and 0xFF) shl 8
            frames += SppFrame(
                id = buf[pos + 3].toInt() and 0xFF,
                payload = buf.copyOfRange(pos + 4, pos + len - 3),
                isFragment = header and 0x2000 != 0,
            )
            pos += len
        }
        buf = buf.copyOfRange(pos, buf.size)
        return frames
    }
}
