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
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The circle itself: the handle a finger drags, and the thing that says where the lookup is
 * aimed. Drawing only, so it can be put on a page without a phone; the drag lives in
 * [HoverController].
 */
open class HoverBubbleView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    /** The dot at the centre: what the circle is aimed at is a point, not the whole disc. */
    private val pip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 255, 255, 255)
    }

    /**
     * Whether a finger is on it. Parked it stays out of the way of the conversation it sits
     * over; dragging it is solid, because then it is the thing being aimed.
     */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val radius = width / 2f - ring.strokeWidth * 1.5f
        // Barely filled on purpose: the circle is aimed at the word under its middle, and a
        // disc would hide the very word it is pointing at.
        fill.color = if (active) GLASS_ACTIVE else GLASS_PARKED
        ring.color = if (active) RIM_ACTIVE else RIM_PARKED
        ring.strokeWidth = if (active) 3 * density else 2 * density
        canvas.drawCircle(width / 2f, height / 2f, radius, fill)
        canvas.drawCircle(width / 2f, height / 2f, radius, ring)
        pip.color = if (active) RIM_ACTIVE else RIM_PARKED
        canvas.drawCircle(width / 2f, height / 2f, if (active) 3 * density else 2 * density, pip)
    }

    private companion object {
        val GLASS_PARKED = Color.argb(40, 31, 111, 235)
        val GLASS_ACTIVE = Color.argb(60, 31, 111, 235)
        val RIM_PARKED = Color.argb(150, 31, 111, 235)
        val RIM_ACTIVE = Color.argb(255, 31, 111, 235)
    }
}

/** The word the circle is over, marked so it is clear which one is being answered. */
class HoverHighlightView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 31, 111, 235)
    }
    private var marked: Rect? = null

    fun mark(bounds: Rect?) {
        marked = bounds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bounds = marked ?: return
        canvas.drawRoundRect(RectF(bounds), 6f, 6f, paint)
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
