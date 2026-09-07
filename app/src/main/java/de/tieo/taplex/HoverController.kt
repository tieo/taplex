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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val readBetterWords: suspend () -> Recognised?,
    /** A picture of the screen, for reading the colours a page draws its own text in. */
    private val readFrame: suspend () -> android.graphics.Bitmap? = { null },
    /** What the apps report, with how much of the screen it accounted for. */
    private val readReported: suspend () -> NodeWords.Reading = {
        NodeWords.Reading(Recognised(emptyList(), "", emptyList()), 0, 0)
    },
    /** The screen read as a picture, for the screens nobody reports positions for. */
    private val readRecognised: suspend () -> Recognised? = { null }
) {

    /** For work that must happen after the frame it was asked in, not during it. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val lookup = Lookup(context)
    private val dictation = Dictation(context)
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

    /** The whole page as one language, up between a tap on the mark and a tap to dismiss. */
    private var page: PageOverlayView? = null
    private var translating: Job? = null

    /** Whether the page moved again while its lines were being read. */
    private var pageAgain = false

    /** The language the page is in, settled once so every scroll does not ask again. */
    private var pageFrom: String? = null
    private var pageInto: String = ""

    /** What each line says in the chosen language, and how the page draws that line. */
    private val said = mutableMapOf<String, String>()
    private val styles = mutableMapOf<String, Pair<Int, Int>>()

    /** When the screen was last pictured, since the system rations screenshots. */
    private var lastFrameAt = 0L

    /** When the screen was last recognised, which is far dearer than reading the tree. */
    private var lastPictureAt = 0L

    /** Whether this page is being read from a picture, which is answered more slowly. */
    private var pageByPicture = false

    /** Lines already asked for, so an overlapping reading does not ask for them again. */
    private val asking = mutableSetOf<String>()

    /** Whether the model for this pair has been fetched, which is done once. */
    private var warmed = false

    /** What the layer is currently showing, so an unchanged page is not redrawn. */
    private var shown = ""

    /** The lines themselves, so a reading that comes to nothing can put them back. */
    private var standing: List<PageOverlayView.Line> = emptyList()

    /** Whether the layer has been taken down and is owed a drawing, changed or not. */
    private var blanked = false

    /** Where each run was last seen, so one that blinks out does not take the page with it. */
    private val placed = LinkedHashMap<String, Seen>()

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
        bubble = view
    }

    /**
     * The keyboard has opened or closed, and the resting mark moves with it.
     *
     * Whenever a keyboard is up the mark rides up by exactly its height, so it holds the
     * same place over the text that is left rather than being shoved onto the keys, and it
     * comes back down to where it rests the moment the keyboard goes. It moves every time,
     * not only when the keyboard would have covered it: a reader who parks the mark low and
     * a reader who parks it high both expect it to get out of the way and then return. The
     * mark being dragged is left alone, since it is under a finger, not resting.
     */
    fun onKeyboard(imeBottomPx: Int) {
        val view = bubble ?: return
        if (view.active) return
        val screen = screenSize()
        val floor = (screen.height() - view.height).coerceAtLeast(0)
        val wanted = if (imeBottomPx > 0) {
            (parkedY - imeBottomPx).coerceIn(0, floor)
        } else {
            parkedY.coerceIn(0, floor)
        }
        if (wanted == bubbleY) return
        Journal.note("keyboard $imeBottomPx px, mark $bubbleY -> $wanted (rest $parkedY)")
        keyboardShift = imeBottomPx
        ValueAnimator.ofInt(bubbleY, wanted).apply {
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
        dismissPage()
        hideLayer()
        bubble?.let { windowManager.removeView(it) }
        bubble = null
    }

    fun close() {
        dictation.close()
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
        // Added off to one side and invisible, not at the top-left corner where a card
        // placed at 0,0 would otherwise flash before it is moved onto the word.
        windowManager.addView(view, cardParams(-10000, 0))
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
            // The card becomes visible only now, placed and sized: never a frame of it
            // sitting below the word before it settles above the circle.
            if (view.alpha < 1f) {
                view.animate().alpha(1f).setDuration(ENTER_MS)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
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

    // ── the whole page in one language ─────────────────────────────────────────────────

    /**
     * A tap on the mark either lays the page's translation over it or, if one is already
     * up, takes it away: the same gesture puts the page into the reader's language and
     * back.
     */
    private fun togglePage() {
        if (page != null) {
            dismissPage()
            return
        }
        translatePage()
    }

    /**
     * Puts the page into the reader's language and keeps it there until it is tapped away.
     *
     * There are two ways a page can be read and they behave differently. Where the app says
     * where each of its characters sits, the lines can be found again on every beat for
     * nothing, and the translation simply stays with them as they move. Where it does not,
     * the screen has to be recognised from a picture, which costs a second or two and
     * cannot be done while a finger is moving the page; that page is put back after it
     * comes to rest instead.
     */
    private fun translatePage() {
        val view = PageOverlayView(context)
        view.say(context.getString(R.string.page_translating))
        val added = runCatching { windowManager.addView(view, pageParams()) }
            .onFailure { Journal.failed("putting the page translation up", it) }
            .isSuccess
        if (!added) return
        page = view
        pageInto = Prefs(context).translatePageInto
        pageFrom = null
        pageByPicture = false
        lastPictureAt = 0L
        warmed = false
        said.clear()
        styles.clear()
        placed.clear()
        asking.clear()
        shown = ""
        Journal.note("page: on, into " + pageInto)
        refreshPage()
        main.postDelayed(tick, POLL_MS)
    }

    /**
     * The page moved, so the lines have moved with it.
     *
     * A page held by what the app reports is read again at once, since that is cheap and the
     * lines are wanted where they now are. A page held from a picture cannot be: recognising
     * one takes longer than the scroll does, and a translation left at the place it was read
     * from is worse than none, so it is taken down while the page is moving and put back
     * once it has settled.
     */
    fun onContentChanged(scrolled: Boolean = false) {
        if (page == null) return
        // Only a scroll moves the lines. A page that merely changed something in place -
        // and a browser reports that many times a second, for a caret, an animation, its
        // own toolbar - would otherwise blank the translation over and over and never get
        // far enough to put it back.
        if (pageByPicture && scrolled) {
            page?.blank()
            blanked = true
            shown = ""
            placed.clear()
        }
        // A page held from a picture is only read again when it has actually moved, or
        // when it has nothing on it yet. Reading it again costs a second or two with the
        // layer down, and a browser reports something changing many times a second, so
        // answering all of those left the page blank far more often than translated.
        if (pageByPicture && !scrolled && shown.isNotEmpty()) return
        main.removeCallbacks(settle)
        main.postDelayed(settle, if (pageByPicture) PICTURE_SETTLE_MS else SETTLE_MS)
        if (translating?.isActive == true) {
            pageAgain = true
            return
        }
        if (!pageByPicture) refreshPage()
    }

    /**
     * The reading that catches the page where the scroll left it.
     *
     * A page held from a picture has just been taken down to be moved, so this reading is
     * the one that puts it back and must not be the one that gets held off: whatever the
     * usual pause between pictures, after a scroll a new one is owed.
     */
    private val settle = Runnable {
        if (page == null) return@Runnable
        if (pageByPicture) lastPictureAt = 0L
        refreshPage()
    }

    /**
     * The page is read again and again for as long as it is being held in another language.
     *
     * Scrolling is supposed to announce itself, and often does, but a list that flings and
     * settles can go quiet with the lines somewhere new, and a page that then keeps its old
     * translation in the old places is worse than none. Reading on a beat does not depend on
     * being told. A reading that finds everything where it was costs one walk of the tree
     * and draws nothing; a page read from pictures is left to its own slower rhythm.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (page == null) return
            if (!pageByPicture && translating?.isActive != true) refreshPage()
            main.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Reads the page where it stands and hands its translation to the layer.
     *
     * Only one reading runs at a time. Two running together each see the same untranslated
     * lines and ask for every one of them again, which on a page whose model still had to be
     * fetched meant the same two dozen strings translated four times over.
     */
    private fun refreshPage() {
        val view = page ?: return
        if (translating?.isActive == true) {
            pageAgain = true
            return
        }
        translating = scope.launch {
            val reading = withContext(Dispatchers.Default) { readReported() }
            // The app's own report is used wherever it carries the screen: it is immediate,
            // exact, and free. A browser, or a mail body drawn inside one, reports its text
            // without saying where any word of it sits, and only that screen is recognised.
            val carries = reading.found.words.size >= MIN_REPORTED &&
                reading.resolvedCharacters >= reading.unresolvedCharacters
            pageByPicture = !carries
            if (carries) fromReport(view, reading.found) else fromPicture(view)
        }.also { job ->
            job.invokeOnCompletion {
                if (pageAgain && page != null) {
                    pageAgain = false
                    main.post { refreshPage() }
                }
            }
        }
    }

    /**
     * The fast way: the app said where every word is, so the lines are gathered from that
     * and follow the page as it moves, at no cost worth speaking of.
     */
    private suspend fun fromReport(view: PageOverlayView, found: Recognised) {
        val screen = screenSize()
        // A view that does not say where its characters sit hands back the whole row, card
        // or banner it lives in, and painting over one of those to place a word hides
        // everything else it held, so a box has to be the shape of a line to count as one.
        val lines = linesOf(found)
            .filter { readable(it.first, screen) }
            .map { (bounds, text) -> Block(bounds, bounds, text, bounds.height()) }
        if (lines.isEmpty()) {
            if (page === view && said.isEmpty()) {
                view.say(context.getString(R.string.page_no_text))
            }
            return
        }
        val from = language(view, found) ?: return
        translate(view, lines, from)
        if (page !== view) return
        // Colours are read off a picture of the screen, and only for lines never drawn
        // before, whose place is therefore still the app's own rather than this layer's.
        val unstyled = lines.filter { it.text !in styles }
        val now = System.currentTimeMillis()
        if (unstyled.isNotEmpty() && now - lastFrameAt > FRAME_GAP_MS) {
            lastFrameAt = now
            withTimeoutOrNull(FRAME_WAIT_MS) { readFrame() }?.let { frame ->
                for (block in unstyled) {
                    PageOverlayView.styleOf(frame, block.ink)?.let { styles[block.text] = it }
                }
                frame.recycle()
            }
        }
        // A tree read twice in a row does not always answer the same: a row mid-layout, a
        // label briefly not "visible to user". Taken at face value the layer would flicker
        // that line in and out several times a second, so a line keeps its last place for a
        // moment after it stops being reported and only then goes.
        val at = System.currentTimeMillis()
        for (block in lines) placed[block.text] = Seen(block, at)
        placed.entries.removeAll { at - it.value.at > GRACE_MS }
        draw(view, placed.values.map { it.block }.filter { Rect.intersects(it.cover, screen) })
    }

    /**
     * The fallback: nobody said where the words are, so the screen is recognised.
     *
     * The layer is taken down before the picture is taken. Otherwise the picture holds this
     * layer's own words, which would be recognised as the page, translated again, and have
     * their colours read off the replacement rather than off the page.
     */
    private suspend fun fromPicture(view: PageOverlayView) {
        val now = System.currentTimeMillis()
        val since = now - lastPictureAt
        if (since < PICTURE_GAP_MS) {
            // Asked for again too soon. The reading is not dropped, it is put off until the
            // picture may be taken, or a page that keeps reporting changes never comes back.
            main.removeCallbacks(settle)
            main.postDelayed(settle, PICTURE_GAP_MS - since)
            return
        }
        lastPictureAt = now
        if (page !== view) return
        view.blank()
        blanked = true
        // One frame for the blanked layer to actually reach the screen before it is caught.
        delay(BLANK_MS)
        // The system rations screenshots and sometimes simply refuses one. The layer has
        // already been taken down to be photographed, so every way out from here puts back
        // what it was showing: a reading that came to nothing must not leave a blank page.
        val frame = withTimeoutOrNull(FRAME_WAIT_MS) { readFrame() } ?: run {
            Journal.note("page: no picture this time, keeping what was up")
            restore(view)
            return
        }
        val found = withContext(Dispatchers.Default) {
            runCatching { Ocr.run(frame) }.getOrNull()
        }
        if (found == null || page !== view) {
            frame.recycle()
            restore(view)
            return
        }
        val screen = screenSize()
        val blocks = found.paragraphs.mapNotNull { paragraph ->
            if (paragraph.text.isBlank()) return@mapNotNull null
            // What is painted over is every word the recogniser actually saw inside this
            // run, not the run's own rectangle: the rectangle is drawn tight around the
            // letters it was sure of, and the tails and edges of the rest sit outside it
            // and were still showing around the replacement.
            val cover = Rect(paragraph.bounds)
            for (word in found.words) {
                if (paragraph.bounds.contains(word.bounds.centerX(), word.bounds.centerY())) {
                    cover.union(word.bounds)
                }
            }
            if (!Rect.intersects(cover, screen)) return@mapNotNull null
            if (!worthReplacing(paragraph.text)) return@mapNotNull null
            Block(cover, cover, paragraph.text, paragraph.lineHeight, tight = true)
        }
        // A recogniser cuts a paragraph wherever its look changes, so a sentence with a
        // link in it comes back in pieces. Pieces that sit directly under one another, in
        // the same column and at the same size, are the paragraph they were cut from, and
        // are put back together before anything is translated: a fragment translated alone
        // is a different sentence from the one it was part of.
        val joined = gather(blocks)
        val whole = joined.filter { block ->
            joined.none { other ->
                other !== block &&
                    (swallows(other.cover, block.cover) || restates(other, block))
            }
        }
        for (block in whole) {
            if (block.text !in styles) {
                PageOverlayView.styleOf(frame, block.ink)?.let { styles[block.text] = it }
            }
        }
        frame.recycle()
        if (whole.isEmpty()) {
            if (page === view && said.isEmpty()) {
                view.say(context.getString(R.string.page_no_text))
            } else {
                restore(view)
            }
            return
        }
        val from = language(view, found) ?: run { restore(view); return }
        translate(view, whole, from)
        if (page !== view) return
        draw(view, whole)
    }

    /** Puts back what the layer was showing before it was taken down to be photographed. */
    private fun restore(view: PageOverlayView) {
        if (page !== view || standing.isEmpty()) return
        blanked = false
        view.show(standing)
    }

    /**
     * Puts back together the runs a recogniser cut out of one paragraph.
     *
     * Two runs belong to the same paragraph when the second begins about one line below the
     * first, in the same column, and its letters are the same size. Anything else - a
     * caption under a picture, the next heading, a column beside this one - fails one of
     * those and is left as it is.
     */
    private fun gather(blocks: List<Block>): List<Block> {
        if (blocks.size < 2) return blocks
        val order = blocks.sortedBy { it.cover.top }
        val out = mutableListOf<Block>()
        for (block in order) {
            val last = out.lastOrNull()
            if (last != null && follows(last, block)) {
                out[out.size - 1] = Block(
                    cover = Rect(last.cover).apply { union(block.cover) },
                    ink = Rect(last.ink).apply { union(block.ink) },
                    text = last.text.trimEnd() + " " + block.text.trimStart(),
                    lineHeight = last.lineHeight,
                    tight = last.tight,
                )
            } else {
                out += block
            }
        }
        return out
    }

    /** Whether [next] is the continuation of [first] rather than something else nearby. */
    private fun follows(first: Block, next: Block): Boolean {
        val tall = first.lineHeight.coerceAtLeast(1)
        if (kotlin.math.abs(first.lineHeight - next.lineHeight) > tall * SAME_SIZE) return false
        val gap = next.cover.top - first.cover.bottom
        if (gap > tall * SAME_PARAGRAPH || gap < -tall) return false
        val left = kotlin.math.abs(first.cover.left - next.cover.left)
        if (left > tall * SAME_COLUMN) return false
        val overlap = minOf(first.cover.right, next.cover.right) -
            maxOf(first.cover.left, next.cover.left)
        return overlap > minOf(first.cover.width(), next.cover.width()) / 2
    }

    /**
     * Whether [whole] already says what [part] says, in a place that overlaps it.
     *
     * A recogniser does not always cut a paragraph the same way twice: it can hand back its
     * first lines as a run of their own and the paragraph entire as another, whose boxes
     * only partly overlap, so neither swallows the other by area. Both drawn puts the same
     * sentences on the page twice at two sizes. The longer one is the one that was written.
     */
    private fun restates(whole: Block, part: Block): Boolean {
        if (part.text.length >= whole.text.length) return false
        if (!Rect.intersects(whole.cover, part.cover)) return false
        return plain(whole.text).contains(plain(part.text))
    }

    /** Text as it compares: one run of lower-case words, however it was spaced or cut. */
    private fun plain(text: String): String =
        text.lowercase().replace(SPACES, " ").trim()

    /** Whether [outer] holds nearly all of [inner], which makes [inner] a repeat of it. */
    private fun swallows(outer: Rect, inner: Rect): Boolean {
        if (outer == inner) return false
        val lap = Rect(inner)
        if (!lap.intersect(outer)) return false
        val area = inner.width().toLong() * inner.height().toLong()
        if (area <= 0L) return false
        val shared = lap.width().toLong() * lap.height().toLong()
        return shared * 100L >= area * SWALLOWED_PERCENT
    }

    /**
     * Whether a run of recognised text is the page rather than the frame around it.
     *
     * A picture of the screen holds the address bar, the clock and the battery as readily as
     * it holds the article, and an address translated word by word is both wrong and in the
     * way. What has no letters in it, and what is plainly a web address, is left alone.
     */
    private fun worthReplacing(text: String): Boolean {
        if (text.none { it.isLetter() }) return false
        val trimmed = text.trim()
        if (WEB_ADDRESS.containsMatchIn(trimmed)) return false
        return trimmed.count { it.isLetter() } >= MIN_LETTERS
    }

    /** The language the page is in, settled once so every reading does not ask again. */
    private suspend fun language(view: PageOverlayView, found: Recognised): String? {
        val from = pageFrom ?: lookup.detect(found.prose())?.also { pageFrom = it } ?: return null
        if (from == pageInto) {
            if (page === view) view.say(context.getString(R.string.page_same_language))
            return null
        }
        return from
    }

    /** Whatever of [blocks] has not been answered yet, asked for all at once. */
    private suspend fun translate(view: PageOverlayView, blocks: List<Block>, from: String) {
        val fresh = blocks.map { it.text }.distinct().filter { it !in said && it !in asking }
        if (fresh.isEmpty()) return
        val startedAt = System.currentTimeMillis()
        asking += fresh
        // The pair's model is fetched once. Where it has to come down first this is the
        // whole wait, so the layer says so rather than sitting blank.
        if (!warmed) {
            if (page === view) view.say(context.getString(R.string.page_translating))
            warmed = lookup.warm(from, pageInto)
        }
        coroutineScope {
            fresh
                .map { text ->
                    async(Dispatchers.Default) { text to lookup.translateText(text, from, pageInto) }
                }
                .awaitAll()
                .forEach { (text, answer) -> if (answer != null) said[text] = answer }
        }
        asking -= fresh.toSet()
        Journal.note(
            "page: " + fresh.size + " new runs " + from + " -> " + pageInto + " in " +
                (System.currentTimeMillis() - startedAt) + "ms" +
                (if (pageByPicture) " (recognised)" else " (reported)")
        )
    }

    /** Hands the layer what it should be showing, and only when that has changed. */
    private fun draw(view: PageOverlayView, blocks: List<Block>) {
        val lines = blocks.mapNotNull { block ->
            val answer = said[block.text] ?: return@mapNotNull null
            // A run whose colours have not been read off the screen yet is left as it is
            // for now. Drawn in a guessed white it is a bright patch in the middle of a
            // page that is not white, which is worse than the line simply not being
            // translated for the moment it takes to picture the screen.
            val style = styles[block.text] ?: return@mapNotNull null
            PageOverlayView.Line(
                bounds = block.cover,
                text = answer,
                background = style.first,
                ink = style.second,
                lineHeight = block.lineHeight,
                tight = block.tight,
            )
        }
        if (lines.isEmpty()) {
            if (said.isEmpty()) view.say(context.getString(R.string.page_none))
            return
        }
        val mark = lines.joinToString("|") { it.bounds.flattenToString() + ":" + it.text.length }
        // An unchanged page is not redrawn, unless the layer was taken down to photograph
        // the page behind it: then the very same lines still have to be put back, or the
        // reading that found nothing had moved leaves a blank page behind it.
        if (mark == shown && !blanked) return
        shown = mark
        blanked = false
        standing = lines
        Journal.note("page: showing " + lines.size + " runs")
        view.show(lines)
    }

    private fun dismissPage() {
        main.removeCallbacks(settle)
        main.removeCallbacks(tick)
        translating?.cancel()
        translating = null
        pageAgain = false
        pageFrom = null
        pageByPicture = false
        warmed = false
        shown = ""
        said.clear()
        styles.clear()
        placed.clear()
        asking.clear()
        page?.let {
            Journal.note("page: off")
            runCatching { windowManager.removeView(it) }
        }
        page = null
    }

    /** A run of text on the page: what to paint over, what it says, and how tall its lines were. */
    private class Block(
        val cover: Rect,
        val ink: Rect,
        val text: String,
        val lineHeight: Int,
        /** Whether [lineHeight] measures the letters rather than the box around them. */
        val tight: Boolean = false,
    )

    /** A run as last seen, so one that blinks out for a beat does not take the page with it. */
    private class Seen(val block: Block, val at: Long)

    /**
     * Whether a box is a line of text worth replacing.
     *
     * A view that does not report where its characters sit hands back its own rectangle for
     * the one word it holds, and that rectangle can be a whole row, a card, or a banner with
     * a picture in it. Painting over one of those to put a word on it hides everything else
     * it contained, so a box has to be the shape of a line before it is treated as one.
     */
    private fun readable(bounds: Rect, screen: Rect): Boolean =
        !bounds.isEmpty &&
            bounds.height() <= screen.height() * TALLEST_LINE &&
            bounds.width() >= bounds.height() / 2

    /**
     * The words grouped back into the runs they were read from, each with the box the whole
     * run covers.
     *
     * Grouped by run rather than by the words themselves: a screen shows the same text in
     * two places often enough - two apps of the same size, two rows saying "On" - and
     * gathering every word of a spelling into one box would stretch a single line across the
     * whole distance between them. The words arrive in the order the tree holds them, so one
     * view's words are next to each other, and a run ends when the text changes or the next
     * word is nowhere near the last.
     */
    private fun linesOf(found: Recognised): List<Pair<Rect, String>> {
        if (found.words.isEmpty()) return emptyList()
        val lines = mutableListOf<Pair<Rect, String>>()
        var key: String? = null
        var box: Rect? = null
        val apart = (APART_DP * density)
        for (word in found.words) {
            val text = word.line.ifBlank { word.text }
            val open = box
            val near = open != null &&
                word.bounds.top < open.bottom + apart &&
                word.bounds.bottom > open.top - apart
            if (text == key && open != null && near) {
                open.union(word.bounds)
                continue
            }
            if (open != null && key != null) lines += Rect(open) to key
            key = text
            box = Rect(word.bounds)
        }
        box?.let { open -> key?.let { lines += Rect(open) to it } }
        return lines
            .filter { it.second.isNotBlank() && !it.first.isEmpty }
            .map { it.first to it.second.trim() }
    }

    /**
     * The window a replaced page is drawn in.
     *
     * It has to let every touch through, since the page underneath is still the page being
     * scrolled, and it has to be fully opaque, since it is covering words that must not
     * read through it. An ordinary overlay cannot be both: the system discards touches that
     * pass through a window more opaque than eight tenths, and dims it to enforce that,
     * which is exactly the original text showing faintly through its own translation. A
     * window belonging to an accessibility service is trusted with that combination, and
     * this is one, so the page is drawn in its own type rather than an app's.
     */
    private fun pageWindowType(): Int =
        if (context is android.accessibilityservice.AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            CaptureService.overlayType()
        }

    private fun pageParams() = WindowManager.LayoutParams(
        MATCH,
        MATCH,
        pageWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // The page underneath is still the page: it is scrolled and tapped through
            // this, which is the whole point of a translation that keeps up with it. Only
            // the mark, in a window of its own, answers a touch.
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
        // Saying it rather than typing it. The words go into the field as they are heard,
        // and the finished phrase is asked for without waiting to be pressed: the field was
        // opened to ask one question and it has now been asked.
        if (dictation.canListen()) {
            dictation.onPartial = { view.heard(it) }
            dictation.onFinal = { phrase ->
                view.heard(phrase)
                view.listening(false)
                asked?.cancel()
                asked = scope.launch { view.show(lookup.say(phrase)) }
            }
            dictation.onState = { on -> view.listening(on) }
            view.onDictate = { dictation.start(lookup.glossLanguage) }
        } else {
            // Without a recogniser or without permission the microphone would be a button
            // that does nothing, so it is not offered; the app asks for the permission.
            view.onDictate = null
        }
        // The gear opens the app, where a language is added and everything else is set:
        // the panel itself stays about saying a word.
        view.onOpenSettings = {
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
        dictation.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            back?.let { input?.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
        }
        back = null
        input?.let { view ->
            // Removing the window does not always take the keyboard with it, and a keyboard
            // left up over a conversation with nothing to type into is the field half gone.
            val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            windowManager.removeView(view)
        }
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
            // Re-seat in the same frame the screen turns, not on the next one: a post would
            // leave the mark one frame at its old coordinates, which in the new orientation
            // is the middle of the screen. The window metrics are already the new ones here.
            reseat()
            // And once more after layout settles, since a rotation can report its metrics a
            // frame late on some devices; a second seat that lands on the same place is not
            // seen, and one that corrects a stale first is the fix.
            post { reseat() }
        }

        private fun reseat() {
            if (active) return
            val screen = screenSize()
            bubbleX = restingX(width)
            parkedY = parkedY.coerceIn(0, screen.height() - height)
            bubbleY = parkedY
            runCatching { windowManager.updateViewLayout(this, bubbleParams(width)) }
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
            // While the page is being held in another language the mark does one thing:
            // put it back. Nothing underneath is Taplex's to answer, so the only way out of
            // a translated page is the handle that started it.
            if (page != null) {
                if (event.actionMasked == MotionEvent.ACTION_UP) dismissPage()
                return true
            }
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
                    // A press that went nowhere is the third question the mark answers:
                    // not one word and not a word to say, but the whole page in the
                    // reader's language. Anything that was open is put away first.
                    if (!dragging && !longPressed) {
                        closeInput()
                        hideLayer()
                        togglePage()
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
                // It appears where it is wanted, above the finger, rather than at the
                // finger and springing up: that spring was one frame of the circle low by
                // the hand before it climbed. The thread still flows up to it from the
                // finger; the ball only leashes from here on, word to word.
                ballX = wantX
                ballY = wantY
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

        /** How long after the last thing heard the page is read once more, settled. */
        const val SETTLE_MS = 260L

        /** How often a held page is read again, whether or not anything announced itself. */
        const val POLL_MS = 180L

        /** How long a line keeps its last place after it stops being reported. */
        const val GRACE_MS = 500L

        /** How often a page that has to be recognised from a picture is read again. */
        const val PICTURE_GAP_MS = 900L

        /** How long after the page stops moving a recognised one is put back. */
        const val PICTURE_SETTLE_MS = 420L

        /** A frame for the layer to go blank before the screen behind it is caught. */
        const val BLANK_MS = 70L

        /** Below this the tree is carrying chrome rather than the page's own words. */
        const val MIN_REPORTED = 3

        /** The tallest a box may be, against the screen, and still be a line of text. */
        const val TALLEST_LINE = 0.09f

        /** How alike two runs must be to be one paragraph: in size, in gap, in column. */
        const val SAME_SIZE = 0.35f
        const val SAME_PARAGRAPH = 1.6f
        const val SAME_COLUMN = 1.2f

        /** Any run of blank space, for comparing two readings of the same sentence. */
        val SPACES = Regex("\\s+")

        /** What an address looks like, which is not prose and is not translated. */
        val WEB_ADDRESS = Regex("(https?://|www\\.|\\w+\\.(com|org|net|nl|de|es|co|io)\\b)")

        /** Below this a run is a glyph or a number rather than something to read. */
        const val MIN_LETTERS = 2

        /** How much of a run must lie inside another before it counts as a repeat of it. */
        const val SWALLOWED_PERCENT = 75L

        /** How far apart two words may be and still belong to the same run of a line. */
        const val APART_DP = 24f

        /** How often the screen may be pictured for colours, and how long to wait for one. */
        const val FRAME_GAP_MS = 800L
        const val FRAME_WAIT_MS = 1200L

        /** How long the circle takes to get back to the side of the screen. */
        const val PARK_MS = 260L

        /** How long an answer takes to arrive, to move to the next word, and to leave. */
        const val ENTER_MS = 160L
        const val MOVE_MS = 190L
        const val LEAVE_MS = 150L
    }
}
