package io.github.magikdidi24.budspopup.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudMotionTest {
    private fun BudMotion.run(seconds: Float) = repeat((seconds * 30).toInt()) { step(1f / 30) }

    @Test
    fun `a bud leaves the case in 1,2 s, then spins`() {
        val bud = BudMotion(startOut = false)
        bud.out = true
        bud.run(1f)
        assertEquals(0.833f, bud.progress, 0.01f)
        assertEquals(0f, bud.spin, 0f) // no spin on the way out
        bud.run(0.2f)
        assertEquals(1f, bud.progress, 0f)
        bud.run(1f)
        assertEquals(60f, bud.spin, 2f)
        assertEquals(1f, bud.away, 0f)
    }

    @Test
    fun `putting it back reverses the path and stops the spin`() {
        val bud = BudMotion(startOut = true)
        bud.run(4f) // 240 degrees, kept within [-180, 180)
        assertEquals(-120f, bud.spin, 2f)
        bud.out = false
        bud.run(0.6f)
        assertTrue(bud.progress in 0.4f..0.6f)
        bud.run(1f)
        assertEquals(0f, bud.progress, 0f)
        assertEquals(0f, bud.rise, 0f)
        bud.step(1f / 30)
        assertEquals(0f, bud.spin, 0f)
    }

    @Test
    fun `each bud follows its own placement`() {
        val left = BudMotion(startOut = false)
        val right = BudMotion(startOut = false)
        left.out = true
        repeat(18) { left.step(1f / 30); right.step(1f / 30) }
        assertEquals(0.5f, left.progress, 0.01f)
        assertEquals(0f, right.progress, 0f)
    }
}
