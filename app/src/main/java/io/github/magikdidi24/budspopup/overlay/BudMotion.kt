package io.github.magikdidi24.budspopup.overlay

import kotlin.math.sign

/**
 * Where one bud is between its slot in the case (progress 0) and its place in the pair beside
 * the case (progress 1). Each bud has its own, so taking the left one out never moves the right one.
 */
class BudMotion(startOut: Boolean) {
    /** Where the bud should go: set from its placement (taken out of the case or not). */
    var out = startOut
    var progress = if (startOut) 1f else 0f
        private set
    /** Lift straight up out of the slot, the first part of the way out. */
    val rise get() = ease((progress / RISE_END).coerceIn(0f, 1f))
    /** Move to the side and stand up, the rest of the way. */
    val away get() = ease(((progress - AWAY_START) / (1f - AWAY_START)).coerceIn(0f, 1f))

    fun step(seconds: Float) {
        val target = if (out) 1f else 0f
        progress = (progress + sign(target - progress) * seconds / DURATION).coerceIn(0f, 1f)
    }

    companion object {
        const val DURATION = 1.2f       // slot to side, or back
        private const val RISE_END = 16f / 36f
        private const val AWAY_START = 12f / 36f

        fun ease(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2
    }
}
