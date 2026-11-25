package io.github.magikdidi24.budspopup.protocol

enum class Placement {
    Disconnected, Wearing, Idle, InCase, InClosedCase, Unknown;

    val inCase get() = this == InCase || this == InClosedCase
    /** Taken out of the case: in an ear or just in the hand. */
    val isOut get() = this == Wearing || this == Idle

    companion object {
        fun of(code: Int) = entries.getOrNull(code)?.takeIf { it != Unknown } ?: Unknown
    }
}

enum class BudsColor { White, Black }

data class BudsStatus(
    val left: Int,
    val right: Int,
    /** 0 is a real, empty case; null only when the byte is not a percentage. */
    val case: Int?,
    val placementLeft: Placement,
    val placementRight: Placement,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    /** Only in the extended status. */
    val color: BudsColor? = null,
) {
    val anyInCase get() = placementLeft.inCase || placementRight.inCase
    val bothOut get() = placementLeft.isOut && placementRight.isOut
    val bothWorn get() = placementLeft == Placement.Wearing && placementRight == Placement.Wearing
    val shutInCase get() = placementLeft == Placement.InClosedCase || placementRight == Placement.InClosedCase
}
