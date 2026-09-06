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
import android.view.animation.DecelerateInterpolator
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
    private val readWords: suspend () -> Recognised,
    private val readBetterWords: suspend () -> Recognised?
) {

    /** For work that must happen after the frame it was asked in, not during it. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val lookup = Lookup(context)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val density = context.resources.displayMetrics.density

    private var bubble: BubbleView? = null
    private var layer: FrameLayout? = null
    private var highlight: HoverHighlightView? = null
    private var mist: MistView? = null
    private var card: EntryView? = null

    /** Runs while the card is travelling from one word's place to the next. */
    private var cardMove: ValueAnimator? = null
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

    /** Where the hand is, which is the half of the screen the answer must stay out of. */
    private var handY: Int = 0

    /** How big the circle around that point is, which the card has to clear. */
    private var aimRadius = 0

    /** Where the bubble sits when it is put up, and where it stays when a drag ends. */
    private var bubbleX = -1
    private var bubbleY = -1
    /** Where the mark rests when no keyboard is pushing it up. */
    private var parkedY = -1
    private var keyboardShift = 0

    val isUp: Boolean get() = bubble != null

    private fun markPx(): Int = (Prefs(context).markSizeDp * density).toInt()

    fun arm() {
        if (bubble != null) return
        // Read once as the circle appears, so the first drag has something to answer with.
        refresh()
        val view = BubbleView(context)
        val size = markPx()
        val screen = screenSize()
        bubbleX = restingX(size)
        if (bubbleY <= 0) bubbleY = screen.height() / 2
        parkedY = bubbleY
        view.masked = false
        runCatching { windowManager.addView(view, bubbleParams(size)) }
            .onFailure { Journal.failed("putting the circle up", it) }
            .onSuccess { Journal.note("circle up at $bubbleX,$bubbleY") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setOnApplyWindowInsetsListener { _, insets ->
                onKeyboard(insets.getInsets(android.view.WindowInsets.Type.ime()).bottom)
                insets
            }
            view.requestApplyInsets()
        }
        bubble = view
    }

    /**
     * The keyboard has opened or closed. If it would sit over the parked mark, the mark
     * rides up just above it, and comes back down when it goes. The mark being dragged is
     * left alone: it is under a finger, not resting.
     */
    private fun onKeyboard(imeBottomPx: Int) {
        val view = bubble ?: return
        if (view.active) return
        val screen = screenSize()
        val keyboardTop = screen.height() - imeBottomPx
        val wanted = if (imeBottomPx > 0 && parkedY + view.height > keyboardTop) {
            (keyboardTop - view.height - (8 * density).toInt()).coerceAtLeast(0)
        } else {
            parkedY
        }
        if (wanted == bubbleY) return
        Journal.note("keyboard $imeBottomPx px, mark $bubbleY -> $wanted")
        val from = bubbleY
        keyboardShift = imeBottomPx
        ValueAnimator.ofInt(from, wanted).apply {
            duration = 180
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                if (!view.isAttachedToWindow || view.active) return@addUpdateListener
                bubbleY = it.animatedValue as Int
                windowManager.updateViewLayout(view, bubbleParams(view.width))
            }
            start()
        }
    }

    /**
     * Re-seat the mark for a side or a size that has just been set, without waiting for a
     * drag: the point of choosing was to see it take.
     */
    fun repark() {
        val view = bubble ?: return
        if (view.active) return
        val size = markPx()
        val screen = screenSize()
        bubbleX = restingX(size)
        parkedY = parkedY.coerceIn(0, screen.height() - size)
        bubbleY = parkedY
        runCatching { windowManager.updateViewLayout(view, bubbleParams(size)) }
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
        card?.let { view -> runCatching { windowManager.removeView(view) } }
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

    /**
     * Reads the screen, and answers again for wherever the circle is now pointing.
     *
     * What the apps report themselves arrives in a few milliseconds and is answered from
     * straight away. Where that does not carry the screen, a recognised picture follows a
     * second or two later and replaces it; a drag that started in the meantime was not
     * left waiting for it.
     */
    private fun refresh() {
        reading = scope.launch {
            val started = System.currentTimeMillis()
            val reported = readWords()
            accept(reported, "reported", started)
            val better = readBetterWords() ?: return@launch
            accept(better, "recognised", started)
        }
    }

    private fun accept(found: Recognised, how: String, started: Long) {
        if (found.words.isEmpty()) return
        words = found.words
        Journal.note(
            "read " + words.size + " words (" + how + ") in " +
                (System.currentTimeMillis() - started) + "ms"
        )
        // The finger has moved on while this was being read; what it is over now is
        // answered with the words that just arrived.
        aim?.let { point ->
            hovered = null
            hoverAt(point.x, point.y)
        }
        scope.launch { lookup.identify(found.prose()) }
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
        aimRadius = markPx() / 2
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
        val flow = MistView(context)
        container.addView(marks, FrameLayout.LayoutParams(MATCH, MATCH))
        container.addView(flow, FrameLayout.LayoutParams(MATCH, MATCH))
        windowManager.addView(container, layerParams())
        layer = container
        highlight = marks
        mist = flow
    }

    /**
     * Everything of ours off the screen, and the mark visible again.
     *
     * The mark is hidden for the length of a drag because it has become the thread. If the
     * layer goes while the thread is still drawing itself home - the card was dismissed, the
     * app changed - nothing is left to say the thread arrived, and the mark stayed invisible
     * for good: a handle that is there, takes touches, and cannot be seen.
     */
    private fun hideLayer(keepCard: Boolean = false) {
        cardMove?.cancel()
        cardMove = null
        if (!keepCard) {
            card?.let { runCatching { windowManager.removeView(it) } }
            card = null
        }
        bubble?.masked = false
        highlight = null
        mist?.clear()
        mist = null
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
        // Answers arrive, they do not appear: a card that is simply there was already there
        // as far as the eye is concerned, and the eye is on the word rather than on it.
        view.alpha = 0f
        view.onTouchedAway = {
            // Not while a finger is on the mark: the touch that begins a drag lands outside
            // the card too, and the drag is about to replace what is in it anyway.
            if (bubble?.active != true) dismissCard()
        }
        windowManager.addView(view, cardParams(0, 0))
        view.animate().alpha(1f).setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator()).start()
        card = view
        return view
    }

    /** The card goes the way it came, and takes the aiming layer with it. */
    private fun dismissCard() {
        val view = card ?: return
        card = null
        cardMove?.cancel()
        cardMove = null
        view.animate().alpha(0f).setDuration(LEAVE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Taken away on the next turn of the loop, like the layer: a window removed
                // from inside the callback that finished its own animation is a surface
                // pulled out from under the frame being drawn on it.
                main.post { runCatching { windowManager.removeView(view) } }
                // Nothing of ours is left over a conversation nobody is asking about -
                // except a thread still drawing itself home, which is taking the mark with
                // it and is about to be gone anyway.
                if (card == null && bubble?.active != true && mist?.isBusy != true) {
                    main.post { hideLayer() }
                }
            }
            .start()
    }

    /**
     * Where the mark rests, which is a side the reader chose rather than the side the hand
     * happened to finish on.
     */
    private fun restingX(size: Int): Int {
        val prefs = Prefs(context)
        val margin = (prefs.markEdgeDp * density).toInt()
        return if (prefs.markOnRight) screenSize().width() - size - margin else margin
    }

    private fun cardParams(x: Int, y: Int, fromBottom: Boolean = false) = WindowManager.LayoutParams(
        (screenSize().width() * 0.82f).toInt(),
        WRAP,
        CaptureService.overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // What ends the card: a touch anywhere else. Without this nothing did, and an
            // answer stayed over the conversation until the mark was found and tapped.
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Anchored by its bottom edge when it goes above something: the card's height is
        // its content's, and a card positioned by its top grows down over the very thing
        // it was meant to clear.
        gravity = (if (fromBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
        this.x = x
        this.y = y
    }

    /**
     * The card sits above the circle, not merely above the word.
     *
     * The circle covers the word it is aimed at and the finger holding it is below that,
     * so everything from the top of the circle downwards is either the answer's subject or
     * the hand: measuring from the word alone puts the card over the circle. It goes below
     * only when there is no room above.
     */
    private fun place(view: EntryView, word: Rect) {
        val screen = screenSize()
        val circle = aim
        val where = CardPlacement.decide(
            screenWidth = screen.width(),
            screenHeight = screen.height(),
            wordTop = word.top,
            wordBottom = word.bottom,
            circleTop = circle?.let { it.y - aimRadius } ?: word.top,
            handY = handY,
            markOnRight = Prefs(context).markOnRight,
            density = density,
        )
        val room = where.room
        val params = if (where.above) {
            cardParams(where.x, screen.height() - where.y, fromBottom = true)
        } else {
            cardParams(where.x, where.y)
        }
        val was = view.layoutParams as? WindowManager.LayoutParams
        cardMove?.cancel()
        cardMove = null
        if (was != null && was.gravity == params.gravity && view.alpha > 0f &&
            CardPlacement.travels(was.x, was.y, params.x, params.y, density)
        ) {
            // The card follows the circle from word to word. Jumping there reads as a
            // second card rather than as the same one moving, and the eye loses it.
            val fromX = was.x
            val fromY = was.y
            cardMove = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MOVE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    if (card !== view) return@addUpdateListener
                    val f = it.animatedValue as Float
                    val step = WindowManager.LayoutParams().apply { copyFrom(params) }
                    step.x = (fromX + (params.x - fromX) * f).toInt()
                    step.y = (fromY + (params.y - fromY) * f).toInt()
                    runCatching { windowManager.updateViewLayout(view, step) }
                }
                start()
            }
        } else {
            windowManager.updateViewLayout(view, params)
        }
        view.post {
            // A card taller than the space it was given scrolls inside it rather than
            // reaching past the edge of the screen.
            if (view.height > room) {
                windowManager.updateViewLayout(view, params.apply { height = room })
            }
            view.scrollTo(0, 0)
        }
    }

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

            // A tap anywhere but the field puts the field away, rather than only sending
            // the keyboard back: the question was not asked, so nothing of it stays.
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    closeInput()
                    return true
                }
                return super.onTouchEvent(event)
            }
        }
        val here = learning()
        view.askFor(Lookup.languageName(here))
        val langs = Dictionary.installed(context)
            .filter { it.first == lookup.glossLanguage }
            .map { it.second }
        view.onAddLanguage = {
            closeInput()
            hideLayer()
            runCatching {
                context.startActivity(
                    android.content.Intent(context, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        view.setLanguages(langs.map { it to Lookup.languageName(it) }, here) { picked ->
            lookup.setLearning(picked)
            view.askFor(Lookup.languageName(picked))
        }
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
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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

        // A rotation keeps the mark's old x and y, which in the new screen is somewhere in
        // the middle. It goes back to the side it lives on, at a y that still fits, the
        // moment the screen turns rather than the next time it is touched.
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
            super.onConfigurationChanged(newConfig)
            if (active) return
            post {
                val screen = screenSize()
                bubbleX = restingX(width)
                parkedY = parkedY.coerceIn(0, screen.height() - height)
                bubbleY = parkedY
                runCatching { windowManager.updateViewLayout(this, bubbleParams(width)) }
                Journal.note("rotated, mark re-seated at $bubbleX,$bubbleY")
            }
        }

        /**
         * The strip the mark occupies belongs to the mark, not to the system.
         *
         * It rests against the edge of the screen, which is where a swipe inwards means
         * "back". Dragging it therefore went back instead of picking it up, at random,
         * depending on how straight the first few pixels were. Claiming the strip stops the
         * system reading a drag that starts on the mark as a gesture of its own.
         */
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
            }
        }

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

        /** The lift is worth saying once a drag, not on every frame of it. */
        private var lifted = false

        /** Whether the current has been struck up out of the mark yet, this gesture. */
        private var formed = false

        // The ball is not nailed to a point above the finger; it is on the end of the
        // thread. It is pulled towards where the finger is holding it, it has weight, and
        // it swings past and settles rather than stopping where the hand stopped - which is
        // what makes the thread read as a leash instead of a stick.
        private var ballX = 0f
        private var ballY = 0f
        private var ballVx = 0f
        private var ballVy = 0f
        private var wantX = 0f
        private var wantY = 0f
        private var fingerAtX = 0f
        private var fingerAtY = 0f
        private var lastSwing = 0L
        private var asked = false

        private val swing = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active) return
                val now = android.os.SystemClock.uptimeMillis()
                val dt = if (lastSwing == 0L) 0.016f else (now - lastSwing) / 1000f
                lastSwing = now
                step(dt.coerceIn(0.001f, 0.05f))
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }

        /** One frame of the leash: pulled towards where it is wanted, and heavy. */
        private fun step(dt: Float) {
            val ax = (wantX - ballX) * STIFFNESS - ballVx * DAMPING
            val ay = (wantY - ballY) * STIFFNESS - ballVy * DAMPING + GRAVITY * density
            ballVx += ax * dt
            ballVy += ay * dt
            ballX += ballVx * dt
            ballY += ballVy * dt
            // Never so far behind that it is somewhere else entirely: a fling would leave
            // the ball halfway up the screen from where the hand is.
            val slack = LEASH_DP * density
            val dx = ballX - wantX
            val dy = ballY - wantY
            val far = kotlin.math.hypot(dx, dy)
            if (far > slack) {
                ballX = wantX + dx / far * slack
                ballY = wantY + dy / far * slack
            }
            val radius = width / 2f
            mist?.follow(fingerAtX, fingerAtY, ballX, ballY, radius)
            // The thread is redrawn every frame because that is what makes it move; what is
            // under the ball is asked half as often, because a word is a hundred times the
            // size of the distance the ball travels in a frame and reading the screen for
            // one is not free.
            asked = !asked
            if (asked) hoverAt(ballX.toInt(), ballY.toInt())
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Journal.note(
                        "circle touched at " + event.rawX.toInt() + "," + event.rawY.toInt() +
                            ", finger covers " + event.touchMajor.toInt() + "px"
                    )
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    longPressed = false
                    lifted = false
                    formed = false
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
                    if (dragging && !longPressed) followFinger(event)
                    if (longPressed) active = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Journal.note(
                        "circle released, dragged=" + dragging + " longPressed=" + longPressed
                    )
                    removeCallbacks(longPress)
                    active = false
                    android.view.Choreographer.getInstance().removeFrameCallback(swing)
                    highlight?.mark(null)
                    // A press that went nowhere puts the circle away rather than leaving a
                    // card sitting over the conversation.
                    if (!dragging && !longPressed) {
                        closeInput()
                        hideLayer()
                    } else if (dragging) {
                        // The answer belongs to the hand that is asking and goes when the
                        // hand does, unless the reader has asked to keep it: then the card
                        // stays where it is and only the thread and the mark go home.
                        val keep = Prefs(context).keepAfterRelease && card != null
                        if (!keep) dismissCard()
                        // The thread falls back into the mark where the mark comes to rest,
                        // and the mark reappears only once it has: no disc slides home.
                        val home = park()
                        // Once the thread is home there is nothing left to draw, so the
                        // layer it was drawn on goes as well: what is left over a
                        // conversation nobody is asking about should be the mark and
                        // nothing else.
                        // Taken away on the next turn of the loop, not inside the frame
                        // that finished it: a view removed from the window manager while
                        // its own frame callback is still running takes the renderer with
                        // it, which on the emulator meant the whole machine went down.
                        mist?.onDissolved = { masked = false; main.post { hideLayer(keepCard = keep) } }
                        mist?.dissolve(home.x.toFloat(), home.y.toFloat())
                        ballVx = 0f
                        ballVy = 0f
                    }
                }
            }
            return true
        }

        /**
         * Where the circle goes when the finger leaves: back to the side it lives on, so
         * what was being read is not left with a disc sitting in the middle of it, and so
         * the handle is where it was last time. The card and the mark stay where they are;
         * only the handle moves.
         */
        fun park(): Point {
            val target = restingX(width)
            val from = bubbleX
            val restY = bubbleY.coerceIn(0, screenSize().height() - height)
            parkedY = restY
            ValueAnimator.ofInt(from, target).apply {
                duration = PARK_MS
                // Comes to rest rather than stopping dead. At a constant speed a thing that
                // stops looks stopped, not settled.
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    if (!isAttachedToWindow) return@addUpdateListener
                    bubbleX = it.animatedValue as Int
                    bubbleY = restY
                    windowManager.updateViewLayout(this@BubbleView, bubbleParams(width))
                }
                start()
            }
            return Point(target + width / 2, restY + height / 2)
        }

        /**
         * The mark stays under the finger that grabbed it, and the circle it aims with
         * rides clear above.
         *
         * Moving the mark itself above the finger would teleport it out from under the
         * thumb the moment a drag began, since it was picked up where it was parked. So the
         * thing being held stays held, and the thing doing the looking is drawn where it
         * can be seen: half the contact patch the screen reports for this touch, plus the
         * circle's own radius, plus a clear bubble's width more, so the word being read
         * stands well above the hand instead of at the edge of it: at the edge, the word is
         * under the knuckle even when the fingertip is clear of it. A number picked by hand
         * would be wrong on the next screen or the next finger; a multiple of the contact
         * patch the screen reports is not.
         */
        private fun followFinger(event: MotionEvent) {
            val size = width
            val covered = event.touchMajor.takeIf { it > 1f }
                ?: (FINGER_INCHES * context.resources.displayMetrics.ydpi)
            val radius = size / 2f
            val lift = covered / 2f + radius + size * CLEARANCE
            if (!lifted) {
                lifted = true
                Journal.note("lift is " + lift.toInt() + "px for a finger of " + covered.toInt() + "px")
            }
            bubbleX = (event.rawX - radius).toInt()
            bubbleY = (event.rawY - radius).toInt()
            windowManager.updateViewLayout(this, bubbleParams(size))
            handY = event.rawY.toInt()
            fingerAtX = event.rawX
            fingerAtY = event.rawY
            wantX = event.rawX
            wantY = event.rawY - lift
            if (!formed) {
                formed = true
                masked = true
                // It starts where the hand is and is thrown up to where it is wanted, so
                // the first thing the ball does is travel the length of the thread.
                ballX = event.rawX
                ballY = event.rawY
                ballVx = 0f
                ballVy = 0f
                lastSwing = 0L
                mist?.form(event.rawX, event.rawY, ballX, ballY, radius)
                android.view.Choreographer.getInstance().postFrameCallback(swing)
            }
        }
    }

    private companion object {
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
        const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT

        /** The circle's width. */
        const val BUBBLE_DP = 40f

        /**
         * What a fingertip covers when the screen will not say. Touchscreens report the
         * contact patch, and this stands in only for the ones that report nothing: a
         * finger pad is around 11mm across, which is what this is in inches of screen.
         */
        const val FINGER_INCHES = 0.43f

        /**
         * How far above the hand the circle rides, as a multiple of its own width, on top of
         * half the contact patch and its own radius.
         */
        const val CLEARANCE = 1.1f

        /** How hard the thread pulls the ball towards where the hand is holding it. */
        const val STIFFNESS = 260f
        /** And how quickly the swing dies away. Under-damped, so it settles by swinging. */
        const val DAMPING = 18f
        /** The weight on the end of it, in dp a second a second. */
        const val GRAVITY = 900f
        /** How far behind the ball may fall before the thread is simply taut, in dp. */
        const val LEASH_DP = 120f

        /** How far off a word the circle may be and still mean it. */
        const val SLACK_DP = 12f

        /** How long the circle takes to get back to the side of the screen. */
        const val PARK_MS = 260L

        /** How long an answer takes to arrive, to move to the next word, and to leave. */
        const val ENTER_MS = 160L
        const val MOVE_MS = 190L
        const val LEAVE_MS = 150L
    }
}
