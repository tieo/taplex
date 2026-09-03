package de.tieo.taplex

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The modal layer: word boxes and the translation popup, over a captured frame or over the
 * live screen. Lives only between a lookup and its dismissal.
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val source: Source,
    private val onDismiss: () -> Unit
) {
    /** Where the words come from, which is also what is drawn under them. */
    sealed interface Source {
        /**
         * A captured frame whose text still has to be recognised. Shown frozen, since the
         * app underneath keeps moving while the overlay is up.
         */
        class Frame(val bitmap: Bitmap) : Source

        /**
         * Words the apps on screen reported themselves, over the frame the screen showed
         * when the lookup started. Their boxes are screen coordinates, so they sit on that
         * frame exactly.
         *
         * The frame is what makes the boxes trustworthy. Putting the overlay up changes the
         * screen underneath: the keyboard closes, a list settles, a layout reflows, and
         * boxes drawn over the live screen then point at where the words used to be. A
         * frozen frame is also what the reader was looking at when they pressed.
         */
        class Reported(
            val found: Recognised,
            val screenWidth: Int,
            val bitmap: Bitmap?
        ) : Source
    }

    private val prefs = Prefs(context)
    private val translator = WordTranslator()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var translation: Job? = null

    private var sourceLanguage: String? = null
    private var dictionary: Dictionary? = null
    private var root: FrameLayout? = null
    private var popup: EntryView? = null
    private lateinit var screen: WordLayerView

    fun show() {
        val frame = when (source) {
            is Source.Frame -> source.bitmap
            is Source.Reported -> source.bitmap
        }
        val sourceWidth = when (source) {
            is Source.Frame -> source.bitmap.width
            is Source.Reported -> source.screenWidth
        }
        screen = WordLayerView(
            context,
            frame,
            sourceWidth,
            onWordTapped = { word -> lookUp(word) },
            onMissTapped = { dismiss() },
            onLongPressed = { openSettings() }
        )

        val container = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.addView(screen, FrameLayout.LayoutParams(MATCH, MATCH))

        val params = WindowManager.LayoutParams(
            MATCH,
            MATCH,
            CaptureService.overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Focusable, so back closes the layer, but kept out of the input method's
            // business: without this, taking focus closes an open keyboard, which moves
            // the screen the boxes were measured against.
            flags = flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            // The layer has to start at the physical top of the screen. A captured frame
            // covers the whole display, so a window inset below the status bar would draw
            // the frame shifted down, showing its captured clock under the live one and
            // putting every box a status bar's height away from the word it belongs to.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        windowManager.addView(container, params)
        root = container

        when (source) {
            is Source.Reported -> accept(source.found)
            is Source.Frame -> scope.launch {
                val found = runCatching { Ocr.run(source.bitmap) }.getOrNull()
                if (found == null) {
                    popupAt(0, 0).showMessage(context.getString(R.string.unreadable))
                    return@launch
                }
                accept(found)
            }
        }
    }

    private fun accept(found: Recognised) {
        screen.setWords(found.words)
        Log.d("Taplex", "words=" + found.words.size + " prose=" + found.prose().take(120))
        DebugState.lookup(found, source is Source.Reported)
        scope.launch {
            sourceLanguage = translator.identify(found.prose(), prefs.sourceLanguage)
            Log.d("Taplex", "source=" + sourceLanguage + " target=" + prefs.targetLanguage)
        }
    }

    /**
     * Recognition keeps the punctuation attached to a word; translating "Küchengerät," is
     * worse than "Küchengerät". Words reported by an app arrive clean and pass through.
     */
    private fun String.stripped(): String =
        trim { !it.isLetterOrDigit() && it != '-' && it != '\'' }.ifEmpty { this }

    private fun lookUp(word: Word) {
        val term = word.text.stripped()
        val line = word.line
        val scale = screen.scale()
        val x = (word.bounds.left * scale).toInt()
        val y = (word.bounds.bottom * scale).toInt()
        popupAt(x, y).showMessage("$term  …")

        translation?.cancel()
        translation = scope.launch {
            // A screenful is the better sample, but if it was inconclusive the word itself
            // is still worth a try.
            val source = sourceLanguage ?: translator.identify(term, prefs.sourceLanguage)
            if (source == null) {
                popupAt(x, y).showMessage(context.getString(R.string.no_source_language))
                return@launch
            }
            explain(term, line, source, x, y)
        }
    }

    /**
     * The entry first, a translation only where there is no entry. A dictionary says what a
     * word means, in how many ways, and how it is used; a translation of a single word
     * pulled out of its sentence is one guess at one of those senses.
     */
    private suspend fun explain(term: String, line: String, source: String, x: Int, y: Int) {
        val target = prefs.targetLanguage
        val pack = withContext(Dispatchers.IO) {
            // The open pack is kept between lookups, but only for the pair it was opened
            // for: a screen in another language must not be answered out of it.
            val open = dictionary
            if (open != null && open.glossLanguage == target && open.wordLanguage == source) {
                open
            } else {
                open?.close()
                dictionary = null
                Dictionary.open(context, target, source)?.also { dictionary = it }
            }
        }
        val entries = withContext(Dispatchers.IO) { pack?.look(term, line).orEmpty() }
        if (entries.isNotEmpty()) {
            val view = popupAt(x, y)
            view.showEntries(term, entries, target, translation = null)
            view.onOpenArticle = { openArticle(entries.first().lemma, target) }
            return
        }

        // A missing pack and a missing word are different answers: one is a word this
        // dictionary does not have, the other is a language with no dictionary at all, and
        // only the second can be fixed by installing something.
        val note = if (pack == null) {
            context.getString(R.string.no_pack, languageName(source), languageName(target))
        } else {
            null
        }
        // Translating a word into the language it is already in hands back the word, which
        // says nothing and reads as an answer.
        val guess = if (source == target) null else translate(term, source, target)
        val view = popupAt(x, y)
        view.showEntries(term, emptyList(), target, translation = guess, note = note)
        view.onOpenArticle = { openArticle(term, target) }
    }

    private suspend fun translate(term: String, source: String, target: String): String? =
        when (val result = translator.translate(term, source, target, allowDownload = true)) {
            is WordTranslator.Result.Ok ->
                // The model hands unknown words back unchanged; that is not a translation.
                result.text.takeIf { !it.equals(term, ignoreCase = true) }
            is WordTranslator.Result.NeedsDownload ->
                context.getString(R.string.downloading_model, result.source, result.target)
            is WordTranslator.Result.Failed -> null
        }

    private fun languageName(tag: String): String =
        java.util.Locale(tag).displayLanguage.ifEmpty { tag }

    /** Long press anywhere on the layer: Taplex's own screen, for languages and packs. */
    private fun openSettings() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        dismiss()
        runCatching { context.startActivity(intent) }
    }

    /** The whole article, for when the entry in the pack is not the end of the question. */
    private fun openArticle(lemma: String, glossLanguage: String) {
        val url = "https://$glossLanguage.wiktionary.org/wiki/" + Uri.encode(lemma)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The overlay covers the browser it is about to open.
        dismiss()
        runCatching { context.startActivity(intent) }
    }

    /**
     * The card, placed under the word it explains and kept inside the screen. It is capped
     * at part of the screen's width and height so the sentence around the word stays
     * readable behind it.
     */
    private fun popupAt(x: Int, y: Int): EntryView {
        val container = root ?: return EntryView(context)
        val density = context.resources.displayMetrics.density
        val view = popup ?: EntryView(context).also {
            container.addView(
                it,
                FrameLayout.LayoutParams(
                    (container.width * 0.82f).toInt().coerceAtLeast((240 * density).toInt()),
                    WRAP
                )
            )
            popup = it
        }
        view.visibility = View.VISIBLE
        view.post {
            val margin = (8 * density).toInt()
            val maxHeight = (container.height * 0.45f).toInt()
            if (view.height > maxHeight) {
                view.layoutParams = view.layoutParams.also { it.height = maxHeight }
                view.requestLayout()
            }
            val maxX = (container.width - view.width - margin).coerceAtLeast(margin)
            val maxY = (container.height - view.height - margin).coerceAtLeast(margin)
            view.x = x.coerceIn(margin, maxX).toFloat()
            view.y = (y + margin).coerceIn(margin, maxY).toFloat()
            view.scrollTo(0, 0)
        }
        return view
    }

    fun dismiss() {
        translation?.cancel()
        scope.cancel()
        translator.close()
        dictionary?.close()
        dictionary = null
        root?.let { windowManager.removeView(it) }
        root = null
        popup = null
        when (source) {
            is Source.Frame -> source.bitmap.recycle()
            is Source.Reported -> source.bitmap?.recycle()
        }
        onDismiss()
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
