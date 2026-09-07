package de.tieo.taplex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * The page itself, in another language.
 *
 * Not a card laid over the text but the text replaced where it stands: each line is covered
 * in the colour of the surface it sits on and rewritten in the colour and size it already
 * had, so the page keeps its own shape and a reader only notices that they can read it. The
 * lines are given again on every scroll, at wherever they have moved to, which is what makes
 * this hold rather than go stale the moment the page moves.
 *
 * Nothing here takes a touch. The page underneath is still the thing being scrolled and
 * tapped; only the mark, in its own window, answers.
 */
class PageOverlayView(context: Context) : View(context) {

    /**
     * One line as it is to be drawn: where it sits now, what it says, and the colours taken
     * off the page where it was read, so it can be put back looking like itself.
     */
    data class Line(
        val bounds: Rect,
        val text: String,
        val background: Int,
        val ink: Int,
        /** How tall one line of the original was, which is what the type is set from. */
        val lineHeight: Int = 0,
    )

    private val density = context.resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val notice = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f * density, 0f, 0f, Color.BLACK)
    }

    private var lines: List<Line> = emptyList()
    private var message: String? = null

    /** A word while the first lines are still coming back, and nothing after that. */
    fun say(text: String?) {
        message = text
        invalidate()
    }

    /** Nothing at all, which is what a page being moved should have over it. */
    fun blank() {
        if (lines.isEmpty() && message == null) return
        lines = emptyList()
        message = null
        invalidate()
    }

    /** The lines as they stand now. Called again on every scroll, with the new places. */
    fun show(found: List<Line>) {
        lines = found
        message = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bleed = BLEED_DP * density
        for (line in lines) {
            if (line.text.isBlank() || line.bounds.isEmpty) continue
            // The surface the line sat on, put back over the words it had. It reaches a
            // little past the letters it covers: a word's box is the ink, and the strokes
            // that leave it - a tail, a dot, the smoothing at an edge - would otherwise
            // still be showing around the replacement.
            // Forced opaque: a colour sampled off a screenshot can carry an alpha, and a
            // surface painted with one lets the words it is meant to replace show through.
            paint.color = line.ink or OPAQUE
            val room = line.bounds.height() + bleed * 2f
            // Sized to the line it replaces, so a heading stays a heading and a footnote a
            // footnote without knowing anything about the app's own type.
            // Set from one of the original's own lines, not from the whole run: a
            // paragraph's box is many lines tall and type sized to it would be enormous.
            val tall = if (line.lineHeight > 0) line.lineHeight else line.bounds.height()
            var size = (tall * TYPE_OF_LINE)
                .coerceIn(MIN_TYPE_DP * density, MAX_TYPE_DP * density)
            // A translation is usually longer than what it replaces. A label with empty
            // page beside it is given that room and stays on its one line, which is what
            // the page looked like; only where there is no room to grow into is it set
            // smaller to fit, and the whole sentence is kept either way.
            paint.textSize = size
            val wanted = kotlin.math.ceil(paint.measureText(line.text)).toInt()
            val reach = (this.width - line.bounds.left - EDGE_DP * density).toInt()
            val cap = minOf(reach, (line.bounds.width() * GROWTH).toInt())
            val width = when {
                wanted <= line.bounds.width() -> line.bounds.width()
                wanted <= cap -> wanted
                else -> line.bounds.width()
            }.coerceAtLeast(1)
            var layout = layout(line.text, width, size)
            var tries = 0
            while (layout.height > room && size > MIN_TYPE_DP * density && tries < SHRINKS) {
                size = (size * 0.88f).coerceAtLeast(MIN_TYPE_DP * density)
                layout = layout(line.text, width, size)
                tries++
            }
            // The surface goes down first, over everything the replacement will cover:
            // the line's own box, and whatever room to the side the longer text took.
            fill.color = line.background or OPAQUE
            canvas.drawRect(
                line.bounds.left - bleed,
                line.bounds.top - bleed,
                line.bounds.left + maxOf(line.bounds.width(), width) + bleed,
                line.bounds.bottom + bleed,
                fill
            )
            canvas.save()
            // Centred in the band it replaces rather than hung from the top, since the
            // original's own line box has padding this cannot see.
            val slack = ((line.bounds.height() - layout.height) / 2f).coerceAtLeast(0f)
            canvas.translate(line.bounds.left.toFloat(), line.bounds.top + slack)
            layout.draw(canvas)
            canvas.restore()
        }
        message?.let { canvas.drawText(it, width / 2f, height * 0.12f, notice) }
    }

    private fun layout(text: String, width: Int, size: Float): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    companion object {
        /** How much of a line's height its letters take up, as type against its own box. */
        private const val TYPE_OF_LINE = 0.62f

        /** How far past a line's own box the surface behind it is painted, in dp. */
        private const val BLEED_DP = 3.5f

        /** The band a replaced line's type is held to, in dp, and how often it may shrink. */
        private const val MIN_TYPE_DP = 8f
        private const val MAX_TYPE_DP = 30f
        private const val SHRINKS = 6

        /** How far past its own width a line may grow, and how near the edge it may reach. */
        private const val GROWTH = 2.6f
        private const val EDGE_DP = 8f

        /**
         * The colours of a line as the page draws it: the surface behind it, and the ink.
         *
         * A node says where its text is and what it says, never what it looks like, so the
         * look is taken from a picture of the screen: the commonest colour inside a line's
         * box is what it sits on, since text is thin and its background is most of the box,
         * and the ink is whatever inside that box stands furthest from it.
         */
        fun styleOf(frame: Bitmap, bounds: Rect): Pair<Int, Int>? {
            val left = bounds.left.coerceIn(0, frame.width - 1)
            val top = bounds.top.coerceIn(0, frame.height - 1)
            val right = bounds.right.coerceIn(left + 1, frame.width)
            val bottom = bounds.bottom.coerceIn(top + 1, frame.height)
            if (right - left < 2 || bottom - top < 2) return null
            val counts = HashMap<Int, Int>()
            val stepX = ((right - left) / 24).coerceAtLeast(1)
            val stepY = ((bottom - top) / 12).coerceAtLeast(1)
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val colour = frame.getPixel(x, y)
                    counts[colour] = (counts[colour] ?: 0) + 1
                    x += stepX
                }
                y += stepY
            }
            if (counts.isEmpty()) return null
            val background = counts.maxByOrNull { it.value }?.key ?: return null
            val ink = counts.keys.maxByOrNull { distance(it, background) } ?: background
            // A box that is all one colour holds no text this can copy; whatever was read
            // there is written in something that will show against it instead.
            val readable = if (distance(ink, background) < MIN_CONTRAST) {
                if (luminance(background) > 0.5f) Color.BLACK else Color.WHITE
            } else {
                ink
            }
            return background to readable
        }

        private const val MIN_CONTRAST = 60f

        /** Every colour taken off the screen is used at full strength. */
        private const val OPAQUE = 0xFF000000.toInt()

        private fun distance(a: Int, b: Int): Float {
            val dr = (Color.red(a) - Color.red(b)).toFloat()
            val dg = (Color.green(a) - Color.green(b)).toFloat()
            val db = (Color.blue(a) - Color.blue(b)).toFloat()
            return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
        }

        private fun luminance(colour: Int): Float =
            (0.299f * Color.red(colour) + 0.587f * Color.green(colour) +
                0.114f * Color.blue(colour)) / 255f
    }
}
