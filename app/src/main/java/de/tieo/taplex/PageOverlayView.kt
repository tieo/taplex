package de.tieo.taplex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View

/**
 * The whole page as one language.
 *
 * A tap on the mark reads every line on screen and lays its translation over it, so a
 * conversation in a language being learned can be read straight through rather than a word
 * at a time. Each line keeps its own place: the translation sits exactly where the original
 * was, over an opaque card the width of that line, so the page reads as itself, only in the
 * reader's own language.
 *
 * Nothing underneath is reachable while this is up. A touch anywhere takes it down again.
 */
class PageOverlayView(context: Context) : View(context) {

    /** One line of the page and what it says in the chosen language. */
    data class Line(val bounds: Rect, val text: String)

    private val density = context.resources.displayMetrics.density

    private val scrim = Paint().apply { color = Color.argb(140, 8, 12, 20) }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF50D1219.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x2A3442.or(0xFF000000.toInt())
    }
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val notice = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EntryView.MUTED
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
    }

    private var lines: List<Line> = emptyList()
    private var laid: List<Placed> = emptyList()
    private var message: String? = null

    /** Where a line was drawn once wrapped: its card, and the text set inside it. */
    private class Placed(val card: RectF, val layout: StaticLayout, val x: Float, val y: Float)

    /** Called on a touch, so the controller can take the overlay away. */
    var onDismiss: () -> Unit = {}

    /** A word while the models work, before any line is ready. */
    fun say(text: String?) {
        message = text
        invalidate()
    }

    /** The lines to show, laid out against the current width. */
    fun show(found: List<Line>) {
        lines = found
        message = null
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (laid.size != lines.size) relayout()
    }

    private fun relayout() {
        val pad = (7 * density).toInt()
        laid = lines.mapNotNull { line ->
            if (line.text.isBlank() || line.bounds.isEmpty) return@mapNotNull null
            // The type is sized to the line it covers, so a heading stays a heading and a
            // footnote a footnote, then held within a band that keeps both readable.
            ink.textSize = (line.bounds.height() * 0.60f).coerceIn(12f * density, 22f * density)
            val inner = line.bounds.width().coerceAtLeast((90 * density).toInt())
            val layout = StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, ink, inner)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            val left = line.bounds.left.toFloat()
            val top = line.bounds.top.toFloat()
            val box = RectF(
                left - pad,
                top - pad,
                left + layout.width + pad,
                top + layout.height + pad
            )
            Placed(box, layout, left, top)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val radius = 6f * density
        for (placed in laid) {
            canvas.drawRoundRect(placed.card, radius, radius, card)
            canvas.drawRoundRect(placed.card, radius, radius, edge)
            canvas.save()
            canvas.translate(placed.x, placed.y)
            placed.layout.draw(canvas)
            canvas.restore()
        }
        message?.let { canvas.drawText(it, width / 2f, height / 2f, notice) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onDismiss()
            return true
        }
        return true
    }
}
