package de.tieo.taplex

/**
 * Where the answer goes, worked out on its own.
 *
 * This is the part of putting a card on screen that can be got wrong without anyone
 * noticing until they are holding the phone: which side of the screen it stands on, whether
 * it clears the hand, and how far it is allowed to move. Kept apart from the window it ends
 * up in so it can be asked the question directly, in a test, instead of being watched for.
 */
object CardPlacement {

    /**
     * @param aboveWord where the card's bottom edge sits when it goes above; the caller
     *   turns that into whatever its window system wants.
     */
    class Where(
        val x: Int,
        val y: Int,
        val above: Boolean,
        val room: Int,
    )

    /**
     * @param screenWidth,screenHeight the display
     * @param wordTop,wordBottom the word being answered
     * @param circleTop the top of the ring aimed at it, or the word's own top
     * @param handY where the finger is, which is what the answer must not hide behind
     * @param markOnRight which side the mark rests on; the answer keeps to the other one
     * @param density pixels per dp
     */
    fun decide(
        screenWidth: Int,
        screenHeight: Int,
        wordTop: Int,
        wordBottom: Int,
        circleTop: Int,
        handY: Int,
        markOnRight: Boolean,
        density: Float,
    ): Where {
        val margin = (8 * density).toInt()
        val maxHeight = (screenHeight * 0.45f).toInt()
        val width = (screenWidth * 0.82f).toInt()
        val maxX = (screenWidth - width - margin).coerceAtLeast(margin)
        // The same column every time, and away from the hand: the hand comes in from the
        // side the mark rests on. Following the word's own left edge put the answer in a
        // new place for every word, so it had to be found again each time.
        val x = if (markOnRight) margin else maxX

        val clearOf = minOf(wordTop, circleTop) - margin
        // Below is where the hand is. Everything from the word down is the circle, the
        // finger holding it and the arm behind that, so the answer goes above whenever there
        // is room to read it there - not merely when there is more room than below, which
        // put it under the hand for any word in the top half of the screen. When there is no
        // room above, it clears the hand rather than the circle.
        val under = maxOf(wordBottom, handY + (36 * density).toInt()) + margin
        val roomAbove = clearOf - margin
        val roomBelow = screenHeight - under - margin
        val above = roomAbove >= minOf(maxHeight, (READABLE_DP * density).toInt()) ||
            roomAbove >= roomBelow
        val room = (if (above) roomAbove else roomBelow).coerceAtMost(maxHeight)
        return Where(x = x, y = if (above) clearOf else under, above = above, room = room)
    }

    /**
     * Whether the answer should travel to a new place or simply be there.
     *
     * Sliding it further than a line or two is worse than putting it there at once: what the
     * eye follows is a card coming from somewhere it never belonged - below the word, on its
     * way up - which is exactly the flicker the sliding was meant to avoid.
     */
    fun travels(fromX: Int, fromY: Int, toX: Int, toY: Int, density: Float): Boolean {
        val step = kotlin.math.abs(toX - fromX) + kotlin.math.abs(toY - fromY)
        return step in 1..(NEAR_DP * density).toInt()
    }

    /** Enough of an answer to be worth reading, in dp: above the word is preferred to below
     *  it down to this, because below it is the hand. */
    const val READABLE_DP = 170f

    /** How far the answer may travel and still read as the same answer moving. */
    const val NEAR_DP = 90f
}
