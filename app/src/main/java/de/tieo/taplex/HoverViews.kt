package de.tieo.taplex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What sits at the edge of the conversation waiting to be dragged: the app's own mark.
 *
 * It is the app rather than a plain dot because it is the app: a nameless circle over
 * someone's chat is a thing they have to remember the meaning of. The aiming circle is a
 * different thing entirely, drawn by [HoverHighlightView] and only while a finger is down.
 */
open class HoverBubbleView(context: Context) : View(context) {

    private val mark = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)

    /** Whether a finger is on it: parked it stays quieter than the conversation under it. */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** While a drag is on, the mark has become the mist; the handle draws nothing. */
    var masked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (masked) return
        val icon = mark ?: return
        icon.setBounds(0, 0, width, height)
        icon.alpha = if (active) 255 else 200
        icon.draw(canvas)
    }
}

/**
 * The word being answered, and the circle aimed at it.
 *
 * The circle belongs here rather than to the thing being dragged: it exists only while a
 * finger is down, it sits well above that finger, and it must not take a touch. Drawing it
 * in the layer that already covers the screen keeps it out of the way of both.
 */
class HoverHighlightView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 31, 111, 235)
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(52, 31, 111, 235)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.argb(255, 31, 111, 235)
    }
    private val pip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(255, 31, 111, 235)
    }

    private var marked: Rect? = null
    private var aimX = 0f
    private var aimY = 0f
    private var aimRadius = 0f

    fun mark(bounds: Rect?) {
        marked = bounds
        invalidate()
    }

    /** Where the circle is, or nothing at all once the finger is gone. */
    fun aim(x: Float, y: Float, radius: Float) {
        aimX = x
        aimY = y
        aimRadius = radius
        invalidate()
    }

    fun stopAiming() {
        aimRadius = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        marked?.let { canvas.drawRoundRect(RectF(it), 6f, 6f, paint) }
        if (aimRadius <= 0f) return
        canvas.drawCircle(aimX, aimY, aimRadius - rim.strokeWidth, glass)
        canvas.drawCircle(aimX, aimY, aimRadius - rim.strokeWidth, rim)
        canvas.drawCircle(aimX, aimY, 3 * density, pip)
    }
}

/**
 * The other question: not what a word on screen means, but the word for something someone
 * wants to say.
 *
 * What comes back is a translation and, under it, that word's own entry, because the
 * translation alone cannot say whether it is the word that was meant: the entry carries the
 * senses, what the word is marked as, and an example of it in use.
 */
open class SayInputView(context: Context) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density

    private val prompt = TextView(context).apply {
        setTextColor(EntryView.MUTED)
        textSize = 13f
    }

    val field = EditText(context).apply {
        setTextColor(Color.WHITE)
        setHintTextColor(EntryView.MUTED)
        hint = context.getString(R.string.say_hint)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        isSingleLine = true
    }

    private val answer = EntryView(context).apply { visibility = GONE }

    /** Called with what was typed, when it is asked for. */
    var onSubmit: (String) -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.popup_bg)
        val pad = (14 * density).toInt()
        setPadding(pad, pad, pad, pad)
        gravity = Gravity.START
        addView(prompt)
        addView(field)
        addView(answer)
        field.setOnEditorActionListener { _, actionId, event ->
            // An Enter key reports its press and its release, and asking twice cancels the
            // first answer on its way back, so only the press counts.
            val asked = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (asked) {
                field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSubmit)
            }
            true
        }
    }

    /** Says which language the answer will come back in, since that is the whole question. */
    fun askFor(language: String) {
        prompt.text = context.getString(R.string.say_prompt, language)
    }

    /**
     * The word that came back, headed by itself rather than by what was typed: the field
     * above already says what was asked, and repeating it under the answer reads as though
     * the app had not understood.
     */
    fun show(said: Explanation) {
        answer.visibility = VISIBLE
        val word = said.entries.firstOrNull()?.lemma ?: said.translation ?: said.term
        answer.showEntries(
            tapped = word,
            entries = said.entries,
            glossLanguage = said.glossLanguage,
            translation = null,
            note = said.note ?: context.getString(R.string.guessed).takeIf {
                said.entries.isEmpty() && said.translation != null
            }
        )
    }
}
