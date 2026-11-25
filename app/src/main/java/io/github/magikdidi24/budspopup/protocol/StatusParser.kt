package io.github.magikdidi24.budspopup.protocol

/**
 * Reads the battery / placement messages of the Galaxy Buds4 Pro. Offsets come from
 * GalaxyBudsClient (Buds4Pro decoders and test captures); other models move some fields.
 */
object StatusParser {
    const val STATUS_UPDATED = 0x60
    const val EXTENDED_STATUS_UPDATED = 0x61
    private const val COLOR_BLACK = 359 // DeviceIds.Buds4ProBlack; 360 white, 361 apricot

    fun parse(frame: SppFrame): BudsStatus? {
        val p = frame.payload
        return when (frame.id) {
            // revision, L, R, coupled, main, placement, case, charging bits
            STATUS_UPDATED -> if (p.size < 7) null else status(p, 1, 5, 6, chargingAt = 7)
            // revision, ear type, L, R, coupled, main, placement, case ... colors at 14/16 ... charging at 43
            EXTENDED_STATUS_UPDATED -> if (p.size < 8) null else status(p, 2, 6, 7, chargingAt = 43).copy(color = color(p))
            else -> null
        }
    }

    private fun status(p: ByteArray, batteryAt: Int, placementAt: Int, caseAt: Int, chargingAt: Int): BudsStatus {
        val placement = p.u8(placementAt)
        val charging = if (chargingAt < p.size) p.u8(chargingAt) else 0
        return BudsStatus(
            left = p.u8(batteryAt),
            right = p.u8(batteryAt + 1),
            case = p.u8(caseAt).takeIf { it <= 100 },
            placementLeft = Placement.of(placement shr 4),
            placementRight = Placement.of(placement and 0x0F),
            leftCharging = charging and 0x10 != 0,
            rightCharging = charging and 0x04 != 0,
            caseCharging = charging and 0x01 != 0,
        )
    }

    private fun color(p: ByteArray): BudsColor? {
        if (p.size < 18) return null
        val right = p.u8(16) or (p.u8(17) shl 8)
        val id = if (right != 0) right else p.u8(14) or (p.u8(15) shl 8)
        return when (id) {
            0 -> null
            COLOR_BLACK -> BudsColor.Black
            else -> BudsColor.White
        }
    }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
}
