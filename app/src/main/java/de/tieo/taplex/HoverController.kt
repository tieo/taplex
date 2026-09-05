package de.tieo.taplex

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.animation.ValueAnimator
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The circle you drag across a conversation.
 *
 * The other way into Taplex freezes the screen and turns every word into a target, which
 * suits reading a page and not a conversation that is still being spoken: the transcript
 * grows while the layer is up, and a modal layer is in the way of answering. Here nothing
 * is frozen and nothing of Taplex's takes a touch except the bubble itself. Dragging the
 * bubble moves it a thumb's width above the finger, so the word under it is the word you
 * can see, and whatever it passes over is explained under it as it goes.
 *
 * A long press on the bubble asks the other question: not what a word on screen means, but
 * what the word is for something you want to say.
 */
class HoverController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val readWords: suspend () -> Recognised
) {

    private val lookup = Lookup(context)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val density = context.resources.displayMetrics.density

    private var bubble: BubbleView? = null
    private var layer: FrameLayout? = null
    private var highlight: HoverHighlightView? = null
    private var card: EntryView? = null
    private var input: View? = null

    /** Held so it can be taken off again: back closes the field, and only while it is up. */
    private var back: OnBackInvokedCallback? = null

    private var words: List<Word> = emptyList()
    private var hovered: Word? = null
    private var pending: Job? = null
    private var asked: Job? = null
    private var reading: Job? = null

    /** Where the circle is pointing, kept so a late reading can still answer it. */
    private var aim: Point? = null

    /** Where the bubble sits when it is put up, and where it stays when a drag ends. */
    private var bubbleX = -1
    private var bubbleY = -1

    val isUp: Boolean get() = bubble != null

    fun arm() {
        if (bubble != null) return
        // Read once as the circle appears, so the first drag has something to answer with.
        refresh()
        val view = BubbleView(context)
        val size = (BUBBLE_DP * density).toInt()
        if (bubbleX < 0) {
            val screen = screenSize()
            bubbleX = screen.width() - size - (16 * density).toInt()
            bubbleY = screen.height() / 2
        }
        runCatching { windowManager.addView(view, bubbleParams(size)) }
            .onFailure { Journal.failed("putting the circle up", it) }
            .onSuccess { Journal.note("circle up at $bubbleX,$bubbleY") }
        bubble = view
    }

    fun disarm() {
        if (bubble != null) Journal.note("circle away")
        reading?.cancel()
        pending?.cancel()
        asked?.cancel()
        closeInput()
        hideLayer()
        bubble?.let { windowManager.removeView(it) }
        bubble = null
    }

    fun close() {
        disarm()
        scope.cancel()
        lookup.close()
    }

    // ── the drag ───────────────────────────────────────────────────────────────────────

    /**
     * Reads the screen as the drag starts rather than once when armed: a conversation adds
     * lines while the bubble sits there, and the words wanted are the ones on screen now.
     */
    private fun beginDrag() {
        // Whatever was answered last is gone before the screen is read, so a card of ours
        // is never in the picture that gets recognised.
        card?.let { windowManager.removeView(it) }
        card = null
        showLayer()
        // What was read last is kept until the new reading lands. A screen whose text has
        // to be recognised takes about a second, and a circle that answers nothing for a
        // second reads as a circle that does not work; the words rarely move under it.
        reading?.cancel()
        // Walking the node tree takes a few hundred milliseconds on a screenful of text,
        // which as a blocking call would be the first frames of the drag dropped. The drag
        // starts at once and the words are answered for as soon as they arrive.
        refresh()
    }

    /** Reads the screen, and answers again for wherever the circle is now pointing. */
    private fun refresh() {
        reading = scope.launch {
            val started = System.currentTimeMillis()
            val found = readWords()
            words = found.words
            Journal.note(
                "read " + words.size + " words in " +
                    (System.currentTimeMillis() - started) + "ms"
            )
            // The finger has moved on while this was being read; what it is over now is
            // answered with the words that just arrived.
            aim?.let { point ->
                hovered = null
                hoverAt(point.x, point.y)
            }
            lookup.identify(found.prose())
        }
    }

    /** The word under the circle, or the nearest one a finger's width away. */
    private fun wordAt(x: Int, y: Int): Word? {
        words.firstOrNull { it.bounds.contains(x, y) }?.let { return it }
        val slack = SLACK_DP * density
        return words
            .map { it to distance(it.bounds, x, y) }
            .filter { it.second <= slack }
            .minByOrNull { it.second }
            ?.first
    }

    private fun distance(bounds: Rect, x: Int, y: Int): Float {
        val dx = when {
            x < bounds.left -> bounds.left - x
            x > bounds.right -> x - bounds.right
            else -> 0
        }
        val dy = when {
            y < bounds.top -> bounds.top - y
            y > bounds.bottom -> y - bounds.bottom
            else -> 0
        }
        return kotlin.math.hypot(dx.toFloat(), dy.toFloat())
    }

    private fun hoverAt(x: Int, y: Int) {
        aim = Point(x, y)
        val word = wordAt(x, y)
        if (word === hovered) return
        hovered = word
        highlight?.mark(word?.bounds)
        pending?.cancel()
        if (word == null) return
        // A tick under the thumb each time the circle takes a new word, since the eye is on
        // the word rather than on the circle.
        bubble?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        showWaiting(word)
        pending = scope.launch {
            val answer = lookup.explain(word.text.stripped(), word.line)
            Journal.note(
                "answered a word of " + word.text.length + " letters with " +
                    answer.entries.size + " entries"
            )
            // The finger moves on while a translation is being fetched; an answer that
            // arrives for a word already left behind is not shown.
            if (hovered === word) show(answer, word.bounds)
        }
    }

    private fun show(answer: Explanation, word: Rect) {
        val view = cardView()
        view.visibility = View.VISIBLE
        view.showEntries(
            tapped = answer.term,
            entries = answer.entries,
            glossLanguage = answer.glossLanguage,
            translation = answer.translation,
            note = answer.note
        )
        place(view, word)
    }

    /** The word, and nothing else yet: a lookup that has to translate takes a moment. */
    private fun showWaiting(word: Word) {
        val view = cardView()
        view.visibility = View.VISIBLE
        view.showMessage(word.text + "  …")
        place(view, word.bounds)
    }

    // ── the windows ────────────────────────────────────────────────────────────────────

    private fun showLayer() {
        if (layer != null) return
        val container = FrameLayout(context)
        val marks = HoverHighlightView(context)
        container.addView(marks, FrameLayout.LayoutParams(MATCH, MATCH))
        windowManager.addView(container, layerParams())
        layer = container
        highlight = marks
    }

    private fun hideLayer() {
        card?.let { windowManager.removeView(it) }
        card = null
        highlight = null
        layer?.let { windowManager.removeView(it) }
        layer = null
        hovered = null
    }

    /**
     * The card is a window of its own rather than part of the layer. A window that lets
     * touches through is held to 80% opacity by the system, which is right for a mark drawn
     * over a word and wrong for text meant to be read; and as its own window the card can
     * be scrolled, which a long entry needs.
     */
    private fun cardView(): EntryView {
        card?.let { return it }
        val view = EntryView(context)
        windowManager.addView(view, cardParams(0, 0))
        card = view
        return view
    }

    private fun cardParams(x: Int, y: Int) = WindowManager.LayoutParams(
        (screenSize().width() * 0.82f).toInt(),
        WRAP,
        CaptureService.overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    /**
     * The card sits under the word it explains, and above it when the word is low enough
     * that a card below would be under the hand holding the circle.
     */
    private fun place(view: EntryView, word: Rect) {
        val screen = screenSize()
        view.post {
            val margin = (8 * density).toInt()
            val maxHeight = (screen.height() * 0.45f).toInt()
            val height = view.height.coerceAtMost(maxHeight)
            val maxX = (screen.width() - view.width - margin).coerceAtLeast(margin)
            val below = word.bottom + margin
            val above = word.top - height - margin
            val top = if (below + height > screen.height() - margin && above > margin) {
                above
            } else {
                below
            }
            val params = cardParams(
                word.left.coerceIn(margin, maxX),
                top.coerceIn(margin, (screen.height() - height - margin).coerceAtLeast(margin))
            )
            if (view.height > maxHeight) params.height = maxHeight
            windowManager.updateViewLayout(view, params)
            view.scrollTo(0, 0)
        }
    }

    /**
     * Laid out in screen coordinates on purpose. Without that the position is measured
     * inside the system's insets, and since the point the circle is aimed at comes from
     * this position while the words are in screen coordinates, the two spaces have to be
     * the same one or the circle answers a word other than the one under it.
     */
    private fun bubbleParams(size: Int) = WindowManager.LayoutParams(
        size,
        size,
        CaptureService.overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bubbleX
        y = bubbleY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    private fun layerParams() = WindowManager.LayoutParams(
        MATCH,
        MATCH,
        CaptureService.overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // Nothing here takes a touch: the conversation underneath stays usable while
            // the circle is out, and only the bubble itself is Taplex's to press.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    private fun screenSize(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(windowManager.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            Rect(0, 0, windowManager.defaultDisplay.width, windowManager.defaultDisplay.height)
        }

    // ── the word you want to say ───────────────────────────────────────────────────────

    /**
     * A field to say it in, and under it the entry for the word that came back. This window
     * takes focus, which is what puts the keyboard up; the drag never does.
     */
    private fun openInput() {
        if (input != null) return
        val view = object : SayInputView(context) {
            // Before the back callback existed, back arrived as a key event; from Android
            // 13 it does not, and a window that answers only one of the two cannot be
            // closed on the other.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    closeInput()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        view.askFor(Lookup.languageName(learning()))
        // Kept apart from the hover's own job: asking for a word and passing over one are
        // two questions, and neither should cancel the other.
        view.onSubmit = { phrase ->
            asked?.cancel()
            asked = scope.launch { view.show(lookup.say(phrase)) }
        }
        windowManager.addView(view, inputParams())
        input = view
        view.field.requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { closeInput() }
            view.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            back = callback
        }
    }

    private fun learning(): String = lookup.learningLanguage() ?: lookup.glossLanguage

    private fun inputParams() = WindowManager.LayoutParams(
        MATCH,
        WRAP,
        CaptureService.overlayType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        y = (72 * density).toInt()
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
    }

    private fun closeInput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            back?.let { input?.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
        }
        back = null
        input?.let { windowManager.removeView(it) }
        input = null
    }

    /**
     * Recognition keeps punctuation attached to a word; a lookup of "cocina," finds
     * nothing. Words an app reported itself arrive clean and pass through.
     */
    private fun String.stripped(): String =
        trim { !it.isLetterOrDigit() && it != '-' && it != '\'' }.ifEmpty { this }

    // ── the bubble ─────────────────────────────────────────────────────────────────────

    /** The circle: a handle to drag, and the thing that says where the lookup is aimed. */
    private inner class BubbleView(context: Context) : HoverBubbleView(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable {
            longPressed = true
            active = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openInput()
        }

        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var longPressed = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Journal.note("circle touched at " + event.rawX.toInt() + "," + event.rawY.toInt())
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    longPressed = false
                    active = true
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    beginDrag()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        kotlin.math.hypot(event.rawX - downX, event.rawY - downY) > slop
                    ) {
                        dragging = true
                        removeCallbacks(longPress)
                    }
                    if (dragging && !longPressed) followFinger(event.rawX, event.rawY)
                    if (longPressed) active = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Journal.note(
                        "circle released, dragged=" + dragging + " longPressed=" + longPressed
                    )
                    removeCallbacks(longPress)
                    active = false
                    // A press that went nowhere puts the circle away rather than leaving a
                    // card sitting over the conversation.
                    if (!dragging && !longPressed) {
                        closeInput()
                        hideLayer()
                    } else if (dragging) {
                        park()
                    }
                }
            }
            return true
        }

        /**
         * Where the circle goes when the finger leaves: back to the nearer side, so what was
         * being read is not left with a disc sitting in the middle of it. The card and the
         * mark stay where they are; only the handle moves.
         */
        fun park() {
            val screen = screenSize()
            val margin = (16 * density).toInt()
            val target = if (bubbleX + width / 2 < screen.width() / 2) {
                margin
            } else {
                screen.width() - width - margin
            }
            val from = bubbleX
            ValueAnimator.ofInt(from, target).apply {
                duration = PARK_MS
                addUpdateListener {
                    if (!isAttachedToWindow) return@addUpdateListener
                    bubbleX = it.animatedValue as Int
                    bubbleY = bubbleY.coerceIn(0, screen.height() - height)
                    windowManager.updateViewLayout(this@BubbleView, bubbleParams(width))
                }
                start()
            }
        }

        /**
         * The circle rides above the finger, since a fingertip covers about a word: the
         * word being looked up has to be one that can still be read while pointing at it.
         */
        private fun followFinger(rawX: Float, rawY: Float) {
            val size = width
            bubbleX = (rawX - size / 2f).toInt()
            bubbleY = (rawY - LIFT_DP * density - size / 2f).toInt()
            windowManager.updateViewLayout(this, bubbleParams(size))
            hoverAt(bubbleX + size / 2, bubbleY + size / 2)
        }
    }

    private companion object {
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
        const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT

        /** The circle's width. */
        const val BUBBLE_DP = 40f

        /**
         * How far above the finger the circle rides. A hand covers more than the word it
         * is pointing at: the line under the fingertip, the line below it, and the card
         * that opens there. Far enough up that the whole answer stays in sight.
         */
        const val LIFT_DP = 64f

        /** How far off a word the circle may be and still mean it. */
        const val SLACK_DP = 12f

        /** How long the circle takes to get back to the side of the screen. */
        const val PARK_MS = 180L
    }
}
