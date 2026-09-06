package de.tieo.taplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the answer stands, asked directly.
 *
 * Every complaint about this card has been about where it appeared - under the hand, in a
 * different place for every word, arriving from below - and each one was seen on a phone
 * rather than caught here, because the arithmetic could only be watched, not asked. It can
 * be asked now.
 */
class CardPlacementTest {

    private val density = 2.75f
    private val width = 1080
    private val height = 2400
    private val margin = (8 * density).toInt()

    private fun decide(
        wordTop: Int,
        markOnRight: Boolean = true,
        lift: Int = 200,
    ) = CardPlacement.decide(
        screenWidth = width,
        screenHeight = height,
        wordTop = wordTop,
        wordBottom = wordTop + 60,
        circleTop = wordTop - 30,
        handY = wordTop + lift,
        markOnRight = markOnRight,
        density = density,
    )

    @Test
    fun `stands on the far side from the hand`() {
        // The hand comes in from the side the mark rests on, so the answer keeps to the
        // other one - and it is the same column whichever word is being read.
        val right = decide(wordTop = 1200, markOnRight = true)
        val left = decide(wordTop = 1200, markOnRight = false)
        assertEquals(margin, right.x)
        assertEquals(width - (width * 0.82f).toInt() - margin, left.x)
        assertTrue(left.x > right.x)
    }

    @Test
    fun `the same column for every word`() {
        val first = decide(wordTop = 400)
        val second = decide(wordTop = 1500)
        assertEquals(first.x, second.x)
    }

    @Test
    fun `goes above a word in the top half rather than under the hand`() {
        // The bug this is here for: with more room below than above, the answer used to go
        // below - which is where the hand holding the circle is.
        val where = decide(wordTop = 700)
        assertTrue("a word 700px down has room above it", where.above)
    }

    @Test
    fun `goes below only when there is no room above`() {
        val where = decide(wordTop = 90)
        assertFalse("nothing fits above a word at the top of the screen", where.above)
    }

    @Test
    fun `below the word means below the hand`() {
        // Not merely below the circle: the finger is a couple of hundred pixels under it.
        val lift = 260
        val wordTop = 60
        val where = decide(wordTop = wordTop, lift = lift)
        assertFalse(where.above)
        assertTrue("clears the finger", where.y > wordTop + lift)
    }

    @Test
    fun `a step to the next line is travelled, a jump across the screen is not`() {
        val line = (30 * density).toInt()
        assertTrue(CardPlacement.travels(0, 0, 0, line, density))
        assertFalse(CardPlacement.travels(0, 0, 0, (400 * density).toInt(), density))
        // Staying where it is is not a move at all.
        assertFalse(CardPlacement.travels(40, 900, 40, 900, density))
    }
}
