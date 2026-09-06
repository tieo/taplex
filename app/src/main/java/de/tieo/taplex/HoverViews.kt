package de.tieo.taplex

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import android.view.inputmethod.EditorInfo
import android.graphics.Typeface
import android.widget.EditText
import android.widget.HorizontalScrollView
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

    /** While a drag is on, the mark has become the thread; the handle draws nothing. */
    var masked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Coming back, it comes back into view: the thread has just drawn itself into
            // this spot, and a mark that blinks into being there instead undoes that.
            animate().cancel()
            if (value) {
                alpha = 1f
            } else {
                alpha = 0f
                animate().alpha(1f).setDuration(220).start()
            }
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

    private companion object {
        /** How long the mark takes to appear or to go. */
        const val FADE_MS = 130L
        /** And to slide from the word it was on to the next one. */
        const val SLIDE_MS = 150L
    }

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

    /** Where the mark is drawn right now, which trails where it has been asked to be. */
    private val shown = RectF()
    private var marked: Rect? = null
    private var settled = false
    private var presence = 0f
    private var travel: ValueAnimator? = null
    private var aimX = 0f
    private var aimY = 0f
    private var aimRadius = 0f

    /**
     * The word under the circle, or none.
     *
     * The mark slides from the word it was on to the word it is on now, and fades rather
     * than blinking at either end. A mark that jumps between words is read as several marks
     * appearing, which is exactly what the eye should not be doing while it follows one.
     */
    fun mark(bounds: Rect?) {
        val was = RectF(shown)
        val hadOne = settled && presence > 0f
        travel?.cancel()
        travel = null
        marked = bounds
        if (bounds == null) {
            if (!hadOne) { presence = 0f; settled = false; invalidate(); return }
            travel = ValueAnimator.ofFloat(presence, 0f).apply {
                duration = FADE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { presence = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        settled = false
                    }
                })
                start()
            }
            return
        }
        val to = RectF(bounds)
        if (!hadOne) {
            shown.set(to)
            settled = true
            travel = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FADE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { presence = it.animatedValue as Float; invalidate() }
                start()
            }
            return
        }
        travel = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                shown.set(
                    was.left + (to.left - was.left) * f,
                    was.top + (to.top - was.top) * f,
                    was.right + (to.right - was.right) * f,
                    was.bottom + (to.bottom - was.bottom) * f,
                )
                presence = 1f
                invalidate()
            }
            start()
        }
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
        if (settled && presence > 0f) {
            paint.alpha = (90 * presence).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(shown, 6f, 6f, paint)
        }
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

    /** The languages a word can be asked for in, when there is more than one to choose. */
    private val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val chipStrip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipRow)
        visibility = GONE
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
        addView(chipStrip)
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
     * The languages there is a dictionary to answer in. With one there is nothing to
     * choose and the row stays hidden; with more, each is a chip and the chosen one is lit.
     */
    fun setLanguages(languages: List<Pair<String, String>>, chosen: String?, onPick: (String) -> Unit) {
        chipRow.removeAllViews()
        if (languages.size < 2) { chipStrip.visibility = GONE; return }
        chipStrip.visibility = VISIBLE
        val pad = (12 * density).toInt()
        val gap = (6 * density).toInt()
        for ((code, name) in languages) {
            val lit = code == chosen
            val chip = TextView(context).apply {
                text = name
                textSize = 13f
                setTypeface(typeface, if (lit) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (lit) android.graphics.Color.WHITE else EntryView.MUTED)
                setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
                setBackgroundResource(if (lit) R.drawable.chip_on else R.drawable.chip_off)
                setOnClickListener { onPick(code) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = gap; lp.topMargin = gap; lp.bottomMargin = gap
            chipRow.addView(chip, lp)
        }
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
