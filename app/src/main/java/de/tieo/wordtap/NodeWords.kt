package de.tieo.wordtap

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the words on screen from the accessibility node tree instead of from a picture of
 * it. A view reports the text it drew and, through
 * [AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY], a rectangle per character,
 * so a word arrives with its exact spelling and its exact box: no capture, no recognition,
 * and no punctuation or language guesswork.
 *
 * Not every view answers that request usefully. Android's own text views do. Chrome hands
 * back one rectangle for a whole paragraph, repeated for each character in it, which places
 * every word of the paragraph in the same box and is worth nothing to a tap. Text like that
 * is reported as unresolved instead of being turned into boxes nobody can hit, and the
 * caller falls back to the screenshot path for the screen.
 */
object NodeWords {

    /** A word is letters and digits, keeping the marks that sit inside one. */
    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")

    /** Per-node cap on the character-location request, which is one binder call per node. */
    private const val MAX_TEXT = 1000

    private const val MAX_NODES = 3000

    /**
     * What one read of the node tree found. [unresolvedCharacters] counts the text that is
     * on screen but whose words could not be placed, which is how the caller tells a screen
     * this can read from one it cannot.
     */
    data class Reading(
        val found: Recognised,
        val resolvedCharacters: Int,
        val unresolvedCharacters: Int
    )

    /**
     * Every visible word in [root]'s tree. Nodes belonging to [skipPackage] are ignored, so
     * WordTap's own overlay never becomes a tap target for itself.
     */
    fun read(
        root: AccessibilityNodeInfo?,
        skipPackage: String,
        screen: Rect
    ): Reading {
        if (root == null) return Reading(Recognised(emptyList(), "", emptyList()), 0, 0)

        val words = mutableListOf<Word>()
        val blocks = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var seen = 0
        var resolved = 0
        var unresolved = 0

        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val node = queue.removeFirst()
            seen++
            if (node.packageName?.toString() != skipPackage && node.isVisibleToUser) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) {
                    val found = wordsIn(node, text).filter { visible(it, node, screen) }
                    if (found.isEmpty()) {
                        unresolved += text.length
                    } else {
                        resolved += text.length
                        words += found
                        blocks += text
                    }
                }
            }
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        return Reading(
            Recognised(words, blocks.joinToString("\n"), blocks),
            resolved,
            unresolved
        )
    }

    /**
     * Whether a word is really on screen where its box says it is.
     *
     * A node reports the text it holds even when part of it is not drawn: a line scrolled
     * out of its own list, a label under the keyboard, a view clipped by the panel it sits
     * in. Such a word is reported at a position nobody can see, which on screen is a box
     * over blank space. Its box has to lie inside the screen and inside the node's own
     * window to count.
     */
    private fun visible(word: Word, node: AccessibilityNodeInfo, screen: Rect): Boolean {
        if (word.bounds.isEmpty) return false
        if (!screen.contains(word.bounds)) return false
        val window = runCatching { node.window }.getOrNull() ?: return true
        val windowBounds = Rect().also { window.getBoundsInScreen(it) }
        return windowBounds.isEmpty || windowBounds.contains(word.bounds)
    }

    private fun wordsIn(node: AccessibilityNodeInfo, text: String): List<Word> {
        val capped = text.take(MAX_TEXT)
        val matches = WORD.findAll(capped).toList()
        if (matches.isEmpty()) return emptyList()

        val characters = characterRects(node, capped.length)
        if (characters == null) {
            // One word fills the node, so the node's own box is that word's box. More than
            // one and every word would share a box, which no tap could tell apart.
            if (matches.size != 1) return emptyList()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            return if (bounds.isEmpty) emptyList() else listOf(Word(matches[0].value, bounds, capped))
        }

        val found = matches.mapNotNull { match ->
            val box = union(characters, match.range) ?: return@mapNotNull null
            Word(match.value, box, capped)
        }
        // One box shared by every word of the node means the view reported the node's own
        // rectangle for each character rather than the characters' own, which no tap can
        // tell apart.
        if (found.size > 1 && found.all { it.bounds == found[0].bounds }) return emptyList()
        return found
    }

    /**
     * The screen rectangle of each character of [node]'s text, or null when the view does
     * not implement the request.
     */
    private fun characterRects(node: AccessibilityNodeInfo, length: Int): Array<RectF?>? {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        val refreshed = runCatching {
            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
        }.getOrDefault(false)
        if (!refreshed) return null

        val parcelables = node.extras
            ?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
            ?: return null
        if (parcelables.isEmpty()) return null
        return Array(parcelables.size) { parcelables[it] as? RectF }
    }

    /**
     * The box around one word. Characters scrolled out of view report no rectangle, and a
     * word split across a line break spans both lines, so only the rectangles that exist
     * and sit on the word's first line count.
     */
    private fun union(characters: Array<RectF?>, range: IntRange): Rect? {
        var box: RectF? = null
        for (i in range) {
            val rect = characters.getOrNull(i) ?: continue
            if (rect.isEmpty) continue
            val current = box
            if (current == null) {
                box = RectF(rect)
            } else if (rect.top < current.bottom && rect.bottom > current.top) {
                current.union(rect)
            }
        }
        val result = box ?: return null
        return Rect(
            result.left.toInt(),
            result.top.toInt(),
            result.right.toInt(),
            result.bottom.toInt()
        )
    }
}
