package io.github.magikdidi24.budspopup.protocol

enum class Placement {
    Disconnected, Wearing, Idle, InCase, InClosedCase, Unknown;

    val inCase get() = this == InCase || this == InClosedCase

    companion object {
        fun of(code: Int) = entries.getOrNull(code)?.takeIf { it != Unknown } ?: Unknown
    }
}

enum class BudsColor { White, Black }

data class BudsStatus(
    val left: Int,
    val right: Int,
    /** Only reported while the case charges or holds a bud. */
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
    val anyWorn get() = placementLeft == Placement.Wearing || placementRight == Placement.Wearing
}
