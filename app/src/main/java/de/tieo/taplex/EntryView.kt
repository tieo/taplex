package de.tieo.taplex

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What a tapped word turns into: the dictionary entry, senses numbered the way the
 * dictionary numbers them, with the marks that separate a usable sense from a dated or
 * regional one, and a way out to the full article.
 *
 * It scrolls, because a common word has more meanings than fit next to it, and it is capped
 * so that it never covers the sentence it is explaining.
 */
class EntryView(context: Context) : ScrollView(context) {

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    var onOpenArticle: (() -> Unit)? = null

    /**
     * A touch that landed anywhere but on this card.
     *
     * The card is a window of its own and takes no focus, so nothing about reading it ever
     * ends it: it sat over the conversation until the mark happened to be tapped. A touch
     * elsewhere is the reader saying they are done with the answer, and it is the same
     * touch they were going to make anyway - it still reaches the app underneath.
     */
    var onTouchedAway: (() -> Unit)? = null

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
            onTouchedAway?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    init {
        setBackgroundResource(R.drawable.popup_bg)
        val pad = dp(16)
        setPadding(pad, dp(14), pad, dp(14))
        isScrollbarFadingEnabled = true
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** A single line, for the states that have nothing to lay out: waiting, or a failure. */
    fun showMessage(text: String) {
        column.removeAllViews()
        column.addView(line(text, size = 16f))
        onOpenArticle = null
    }

    /**
     * [tapped] is the word as it stands in the text; the entries are what it leads to, which
     * may be its lemma. [translation] is only shown when there is no entry to show instead,
     * since a machine translation of a word out of its sentence is a guess and the entry is
     * not.
     */
    fun showEntries(
        tapped: String,
        entries: List<Entry>,
        glossLanguage: String,
        translation: String?,
        note: String? = null
    ) {
        column.removeAllViews()

        if (entries.isEmpty()) {
            // A tag, not a sentence. Someone holding a finger over a word is reading the
            // word, and a line of prose explaining why there is no entry is a line they
            // have to read instead of the answer they asked for.
            column.addView(
                headline(tapped, note ?: context.getString(R.string.no_entry), null)
            )
            if (!translation.isNullOrBlank()) {
                column.addView(
                    headline(translation, context.getString(R.string.guessed), null)
                        .apply { setPadding(0, dp(6), 0, 0) }
                )
            }
            column.addView(
                line(context.getString(R.string.open_article, glossLanguage), size = 13f, color = LINK)
                    .apply {
                        setPadding(0, dp(10), 0, 0)
                        setOnClickListener { onOpenArticle?.invoke() }
                    }
            )
            return
        }

        for ((index, entry) in entries.withIndex()) {
            if (index > 0) column.addView(divider())
            column.addView(headline(entry.lemma, entry.pos, entry.ipa))
            val form = when {
                entry.label != null -> context.getString(R.string.form_of, tapped, entry.label)
                !entry.lemma.equals(tapped, ignoreCase = true) ->
                    context.getString(R.string.tapped_form, tapped)
                else -> null
            }
            if (form != null) column.addView(line(form, size = 12f, color = MUTED))
            for ((number, sense) in entry.senses.withIndex()) {
                column.addView(senseLine(number + 1, sense))
                for (example in sense.examples) {
                    column.addView(exampleLine(example))
                }
            }
        }

        column.addView(
            line(context.getString(R.string.open_article, glossLanguage), size = 13f, color = LINK)
                .apply {
                    setPadding(0, dp(10), 0, 0)
                    setOnClickListener { onOpenArticle?.invoke() }
                }
        )
    }

    private fun headline(word: String, pos: String?, ipa: String?): TextView {
        val text = SpannableStringBuilder(word)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!pos.isNullOrBlank()) {
            val start = text.length
            text.append("  ").append(pos)
            text.setSpan(ForegroundColorSpan(MUTED), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!ipa.isNullOrBlank()) {
            val start = text.length
            text.append("  ").append(ipa)
            text.setSpan(ForegroundColorSpan(MUTED), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return line(text, size = 19f)
    }

    private fun senseLine(number: Int, sense: Sense): TextView {
        val text = SpannableStringBuilder("$number  ")
        text.setSpan(ForegroundColorSpan(LINK), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(0.85f), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sense.tags.isNotEmpty()) {
            val start = text.length
            text.append(sense.tags.joinToString(", ")).append("  ")
            text.setSpan(ForegroundColorSpan(TAG), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.85f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append(sense.gloss)
        return line(text, size = 15f).apply { setPadding(0, dp(7), 0, 0) }
    }

    private fun exampleLine(example: String): TextView =
        line(example, size = 13f, color = MUTED).apply {
            setTypeface(typeface, Typeface.ITALIC)
            setPadding(dp(18), dp(3), 0, 0)
        }

    private fun divider(): View =
        View(context).apply {
            setBackgroundColor(RULE)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(12) }
        }

    private fun line(text: CharSequence, size: Float, color: Int = TEXT): TextView =
        TextView(context).apply {
            setText(text)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.START
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /** The same colours the app's own screen uses, so the two look like one app. */
        val TEXT = Color.rgb(231, 236, 243)

        /** The colour of everything that is not the answer itself. */
        val MUTED = Color.rgb(139, 151, 168)

        /** What a sense is marked as: the marker colour, as on the screen. */
        val TAG = Color.rgb(255, 197, 61)
        val LINK = Color.rgb(76, 154, 255)
        val RULE = Color.rgb(34, 43, 56)
    }
}
