package de.tieo.wordtap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * The word boxes, and under them either a captured frame or the live screen.
 *
 * A captured frame is drawn frozen on purpose: the app underneath keeps scrolling, so live
 * coordinates would drift away from the ones the recogniser returned. Words read from the
 * node tree need no frame, because their boxes are screen coordinates the app itself
 * reported, and the live screen shows through the scrim instead.
 *
 * [sourceWidth] is the width the word boxes were measured in, so boxes and frame scale
 * together onto however wide this view ends up.
 */
class WordLayerView(
    context: Context,
    private val frame: Bitmap?,
    private val sourceWidth: Int,
    private val onWordTapped: (Word) -> Unit,
    private val onMissTapped: () -> Unit,
    private val onLongPressed: () -> Unit
) : View(context) {

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(event: MotionEvent) {
                longPressed = true
                onLongPressed()
            }
        }
    )

    private var words: List<Word> = emptyList()
    private var highlighted: Word? = null

    /** A long press has already been answered; the release that follows is not a tap. */
    private var longPressed = false

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 255, 255, 255)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 31, 111, 235)
    }
    private val dimPaint = Paint().apply { color = Color.argb(60, 0, 0, 0) }

    fun setWords(found: List<Word>) {
        words = found
        invalidate()
    }

    fun scale(): Float =
        if (sourceWidth == 0) 1f else width.toFloat() / sourceWidth.toFloat()

    override fun onDraw(canvas: Canvas) {
        val s = scale()
        if (frame != null) {
            canvas.save()
            canvas.scale(s, s)
            canvas.drawBitmap(frame, 0f, 0f, null)
            canvas.restore()
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        for (word in words) {
            val paint = if (word === highlighted) highlightPaint else boxPaint
            canvas.drawRoundRect(word.bounds.scaled(s), 4f, 4f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestures.onTouchEvent(event)) return true
        if (event.action != MotionEvent.ACTION_UP) return true
        if (longPressed) {
            longPressed = false
            return true
        }
        val s = scale()
        val hit = words.firstOrNull { it.bounds.scaled(s).contains(event.x, event.y) }
        if (hit == null) {
            onMissTapped()
        } else {
            highlighted = hit
            invalidate()
            onWordTapped(hit)
        }
        return true
    }

    private fun Rect.scaled(s: Float) =
        RectF(left * s, top * s, right * s, bottom * s)
}
