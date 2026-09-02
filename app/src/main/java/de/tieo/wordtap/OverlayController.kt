package de.tieo.wordtap

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
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

/**
 * The modal layer: frozen frame, word boxes, and the translation popup. Lives only between
 * a bubble tap and its dismissal.
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val frame: Bitmap,
    private val onDismiss: () -> Unit
) {
    private val prefs = Prefs(context)
    private val translator = WordTranslator()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var translation: Job? = null

    private var sourceLanguage: String? = null
    private var root: FrameLayout? = null
    private var popup: TextView? = null
    private lateinit var screen: FrozenScreenView

    fun show() {
        screen = FrozenScreenView(
            context,
            frame,
            onWordTapped = { word -> lookUp(word) },
            onMissTapped = { dismiss() }
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
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(container, params)
        root = container

        scope.launch {
            val found = runCatching { Ocr.run(frame) }.getOrNull()
            if (found == null) {
                showPopup("Could not read the screen", 0, 0)
                return@launch
            }
            screen.setWords(found.words)
            Log.d("WordTap", "ocr words=" + found.words.size + " prose=" + found.prose().take(120))
            sourceLanguage = translator.identify(found.prose(), prefs.sourceLanguage)
            Log.d("WordTap", "source=" + sourceLanguage + " target=" + prefs.targetLanguage)
        }
    }

    /** OCR keeps the punctuation attached to a word; translating "Küchengerät," is worse than "Küchengerät". */
    private fun String.stripped(): String =
        trim { !it.isLetterOrDigit() && it != '-' && it != '\'' }.ifEmpty { this }

    private fun lookUp(word: Word) {
        val term = word.text.stripped()
        val scale = if (frame.width == 0) 1f else (root?.width ?: frame.width).toFloat() / frame.width
        val x = (word.bounds.left * scale).toInt()
        val y = (word.bounds.bottom * scale).toInt()
        showPopup("$term  …", x, y)

        translation?.cancel()
        translation = scope.launch {
            // A screenful is the better sample, but if it was inconclusive the word itself
            // is still worth a try.
            val source = sourceLanguage ?: translator.identify(term, prefs.sourceLanguage)
            if (source == null) {
                showPopup("Source language not recognised", x, y)
                return@launch
            }
            val target = prefs.targetLanguage
            when (val result = translator.translate(term, source, target, allowDownload = true)) {
                is WordTranslator.Result.Ok ->
                    showPopup("$term\n${result.text}", x, y)
                is WordTranslator.Result.NeedsDownload ->
                    showPopup("Downloading ${result.source} to ${result.target} model…", x, y)
                is WordTranslator.Result.Failed ->
                    showPopup("$term\n(${result.reason})", x, y)
            }
        }
    }

    private fun showPopup(text: String, x: Int, y: Int) {
        val container = root ?: return
        val view = popup ?: TextView(context).apply {
            setBackgroundResource(R.drawable.popup_bg)
            setTextColor(Color.WHITE)
            textSize = 16f
            val pad = (12 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            container.addView(this, FrameLayout.LayoutParams(WRAP, WRAP))
            popup = this
        }
        view.text = text
        view.visibility = View.VISIBLE
        view.post {
            val margin = (8 * context.resources.displayMetrics.density).toInt()
            val maxX = (container.width - view.width - margin).coerceAtLeast(margin)
            val maxY = (container.height - view.height - margin).coerceAtLeast(margin)
            view.x = x.coerceIn(margin, maxX).toFloat()
            view.y = (y + margin).coerceIn(margin, maxY).toFloat()
        }
    }

    fun dismiss() {
        translation?.cancel()
        scope.cancel()
        translator.close()
        root?.let { windowManager.removeView(it) }
        root = null
        popup = null
        frame.recycle()
        onDismiss()
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
