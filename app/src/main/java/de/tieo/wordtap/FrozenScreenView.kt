package de.tieo.wordtap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Shows the captured frame and the boxes of the words found in it. The frame is frozen on
 * purpose: the app underneath keeps scrolling, so live coordinates would drift away from
 * the ones the recogniser returned.
 */
class FrozenScreenView(
    context: Context,
    private val frame: Bitmap,
    private val onWordTapped: (Word) -> Unit,
    private val onMissTapped: () -> Unit
) : View(context) {

    private var words: List<Word> = emptyList()
    private var highlighted: Word? = null

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

    private fun scale(): Float =
        if (frame.width == 0) 1f else width.toFloat() / frame.width.toFloat()

    override fun onDraw(canvas: Canvas) {
        val s = scale()
        canvas.save()
        canvas.scale(s, s)
        canvas.drawBitmap(frame, 0f, 0f, null)
        canvas.restore()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        for (word in words) {
            val paint = if (word === highlighted) highlightPaint else boxPaint
            canvas.drawRoundRect(word.bounds.scaled(s), 4f, 4f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
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
