package de.tieo.taplex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The circle you aim with, and the thread of light that holds it to your hand.
 *
 * The mark parked at the edge is the app's own icon. When it is dragged, light runs up the
 * gap between the finger and the word along a few drawn strands, and gathers into a ring
 * around the word. The strands are lines rather than a shower of sparks: a scatter of hot
 * motes reads as fire, and this is not fire - it is a connection between the hand and the
 * word, the kind that is drawn as a thread. They carry the colours sampled from the launcher
 * icon, so the light is the mark's own and not a colour chosen for it.
 *
 * The strands breathe: each carries a wave that travels along it, widest in the middle of
 * the span and still at both ends, so the thread is alive without ever leaving its path. A
 * few pulses of light run up them, which is what gives the direction - from the hand to the
 * word while it is held, and back down into the mark when it is let go.
 *
 * It lives in the layer that already covers the screen and takes no touch, so it is drawn
 * here rather than owning a window: a frame clock advances the wave, the ring rides the
 * finger, and on release the whole thing draws back into the mark.
 */
class MistView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    // ── the ring ─────────────────────────────────────────────────────────────────────
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7 * density
    }
    private val pip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ── the strands ──────────────────────────────────────────────────────────────────
    /** The thread itself: thin, bright, drawn last. */
    private val thread = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * The light around the thread, laid on as two wider and fainter strokes of the same
     * line rather than as a blur.
     *
     * A blur mask is not something the hardware can draw: a path drawn with one is rendered
     * in software, every frame, over the whole screen. Widening the same path twice costs
     * nothing by comparison and reads the same at arm's length.
     */
    private val threadGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pulseGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val stroke = Path()

    /**
     * One gradient per strand, made once and moved into place each frame.
     *
     * A shader built per strand per frame is seven allocations sixty times a second, and
     * the renderer does not survive that: the emulator's software Vulkan died on the first
     * drag. Built in a unit space instead - from the hand at 0 to the word at 1 - and
     * mapped onto wherever the thread runs now with a matrix that is reused too.
     */
    private val gradients = arrayOfNulls<LinearGradient>(STRANDS)
    private val onto = Matrix()
    private val unitFrom = floatArrayOf(0f, 0f, 0f, 1f)
    private val unitTo = FloatArray(4)

    // ── where things are ─────────────────────────────────────────────────────────────
    private var fingerX = 0f
    private var fingerY = 0f
    private var ringX = 0f
    private var ringY = 0f
    private var ringRadius = 0f

    /** 0 while parked, 1 while a ring is fully formed; the animators run it between. */
    private var presence = 0f

    /** Called once the thread has drawn all the way back into the mark. */
    var onDissolved: (() -> Unit)? = null

    private enum class Phase { GONE, FORMING, LIVE, DISSOLVING }
    private var phase = Phase.GONE
    private var phaseStart = 0L
    private var homeX = 0f
    private var homeY = 0f

    /** How long the thread has been running, which is what the waves are read from. */
    private var age = 0f

    // ── the strands, and the colours the mark lends them ─────────────────────────────
    private val palette: IntArray = sampleIconColours(context)
    private val ends = FloatArray(STRANDS)      // where on the ring each strand lands
    private val sway = FloatArray(STRANDS)      // how far its wave carries it off the path
    private val waves = FloatArray(STRANDS)     // how many waves fit along it
    private val drift = FloatArray(STRANDS)     // how fast the wave travels
    private val phaseOf = FloatArray(STRANDS)
    private val hue = IntArray(STRANDS)
    private val weight = FloatArray(STRANDS)    // how thick, and so how present
    private val riding = FloatArray(STRANDS)    // where the pulse on it has got to

    private var random = java.util.Random()

    private var lastFrame = 0L
    private val clock = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            advance(dt.coerceAtMost(0.05f))
            invalidate()
            if (phase != Phase.GONE) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        reseed()
    }

    // ── the drag tells it these ──────────────────────────────────────────────────────

    /** The thread is drawn up out of the mark, towards the word. */
    fun form(fingerX: Float, fingerY: Float, ringX: Float, ringY: Float, radius: Float) {
        this.fingerX = fingerX; this.fingerY = fingerY
        this.ringX = ringX; this.ringY = ringY; this.ringRadius = radius
        if (phase == Phase.LIVE || phase == Phase.FORMING) return
        reseed()
        age = 0f
        phase = Phase.FORMING
        phaseStart = now()
        start()
    }

    /** Every frame of the drag: the ring rides the finger and the thread follows. */
    fun follow(fingerX: Float, fingerY: Float, ringX: Float, ringY: Float, radius: Float) {
        this.fingerX = fingerX; this.fingerY = fingerY
        this.ringX = ringX; this.ringY = ringY; this.ringRadius = radius
    }

    /** The finger is gone: the thread draws back into the mark at [homeX],[homeY]. */
    fun dissolve(homeX: Float, homeY: Float) {
        if (phase == Phase.GONE || phase == Phase.DISSOLVING) return
        this.homeX = homeX; this.homeY = homeY
        phase = Phase.DISSOLVING
        phaseStart = now()
    }

    /**
     * Put the thread where it would be a given moment after it was struck, without a clock.
     *
     * The book's pictures are rendered on the JVM, where nothing advances a frame, and a
     * state that cannot be photographed is a state nobody looks at: this animation went
     * unexamined for exactly that reason. Seeded, so the same moment draws the same thread
     * every time and a picture can be compared with the one before it.
     */
    fun poseAt(
        seconds: Float,
        fingerX: Float,
        fingerY: Float,
        ringX: Float,
        ringY: Float,
        radius: Float,
        seed: Long = 7L,
    ) {
        random = java.util.Random(seed)
        reseed()
        this.fingerX = fingerX; this.fingerY = fingerY
        this.ringX = ringX; this.ringY = ringY; this.ringRadius = radius
        age = seconds
        val forming = (seconds * 1000f / FORM_MS).coerceIn(0f, 1f)
        presence = ease(forming)
        phase = if (forming >= 1f) Phase.LIVE else Phase.FORMING
        for (i in 0 until STRANDS) {
            riding[i] = ((riding[i] + seconds * (PULSE_MIN + (i % 3) * 0.18f)) % 1.6f) - 0.25f
        }
        invalidate()
    }

    fun clear() {
        phase = Phase.GONE
        presence = 0f
        Choreographer.getInstance().removeFrameCallback(clock)
        lastFrame = 0L
        invalidate()
    }

    // ── the simulation ───────────────────────────────────────────────────────────────

    private fun start() {
        lastFrame = 0L
        Choreographer.getInstance().removeFrameCallback(clock)
        Choreographer.getInstance().postFrameCallback(clock)
    }

    private fun advance(dt: Float) {
        when (phase) {
            Phase.FORMING -> {
                val p = ((now() - phaseStart) / FORM_MS.toFloat()).coerceIn(0f, 1f)
                presence = ease(p)
                if (p >= 1f) phase = Phase.LIVE
            }
            Phase.LIVE -> presence = 1f
            Phase.DISSOLVING -> {
                val p = ((now() - phaseStart) / DISSOLVE_MS.toFloat()).coerceIn(0f, 1f)
                presence = 1f - ease(p)
                if (p >= 1f) {
                    val done = onDissolved
                    clear()
                    done?.invoke()
                    return
                }
            }
            Phase.GONE -> return
        }
        age += dt
        for (i in 0 until STRANDS) {
            riding[i] += dt * (PULSE_MIN + (i % 3) * 0.18f)
            if (riding[i] > 1.35f) riding[i] -= 1.6f
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (phase == Phase.GONE || presence <= 0f) return

        // Where the thread runs from: the hand while it holds the ring, the mark it is
        // being drawn back into once the hand is gone.
        val fromX: Float; val fromY: Float
        if (phase == Phase.DISSOLVING) { fromX = homeX; fromY = homeY } else { fromX = fingerX; fromY = fingerY }
        val (px, py) = perpendicular(fromX, fromY, ringX, ringY)
        val span = hypot(ringX - fromX, ringY - fromY).coerceAtLeast(1f)

        for (i in 0 until STRANDS) {
            drawStrand(canvas, i, fromX, fromY, px, py, span)
        }

        drawRing(canvas)
    }

    /**
     * One filament, from the hand to a point on the ring.
     *
     * The line itself is a curve bowed a little to one side, and the wave is added across
     * it: an offset that grows and falls away over the length so the thread is anchored at
     * both ends and free in the middle. Sampling it in a couple of dozen steps is enough for
     * a line this long to read as smooth.
     */
    private fun drawStrand(
        canvas: Canvas,
        i: Int,
        fromX: Float,
        fromY: Float,
        px: Float,
        py: Float,
        span: Float,
    ) {
        val a = ends[i]
        val toX = ringX + cos(a) * ringRadius
        val toY = ringY + sin(a) * ringRadius
        // A wave the size of the journey. The same swing that reads as a slow drift across
        // a long reach ties itself in knots across a short one, and the thread is at its
        // shortest exactly when it is most looked at.
        val scale = (span / (REFERENCE_SPAN * density)).coerceIn(0.3f, 1.15f)
        // The whole thread bows to one side, each strand a little differently, so several
        // of them read as a braid rather than as one line drawn several times.
        val bow = (i - (STRANDS - 1) / 2f) / STRANDS * 2f
        val cx = (fromX + toX) / 2f + px * span * BOW * bow
        val cy = (fromY + toY) / 2f + py * span * BOW * bow

        // While forming it reaches: the far end is only as far along as the light has got.
        val reach = if (phase == Phase.FORMING) presence else 1f

        stroke.rewind()
        var sx = 0f
        var sy = 0f
        val steps = SAMPLES
        for (s in 0..steps) {
            val u = (s.toFloat() / steps) * reach
            val wave = sin(phaseOf[i] + u * waves[i] * TAU - age * drift[i]) *
                sway[i] * density * scale * bell(u)
            val x = quad(fromX, cx, toX, u) + px * wave
            val y = quad(fromY, cy, toY, u) + py * wave
            if (s == 0) { stroke.moveTo(x, y); sx = x; sy = y } else stroke.lineTo(x, y)
        }
        if (sx == 0f && sy == 0f) return

        // Brightest in the middle of its life, and never quite solid: a thread of light is
        // read by its glow as much as by its line.
        val breath = 0.72f + 0.28f * sin(age * BREATH + phaseOf[i])
        val alpha = (presence * weight[i] * breath * 210f).toInt().coerceIn(0, 235)
        val colour = hue[i]

        // From the mark's own colour at the hand to the ring's at the word: what the thread
        // is for is that the two are the same thing, and a line that changes along its
        // length says so where a line of one colour does not.
        val paint = gradients[i]
        unitTo[0] = fromX; unitTo[1] = fromY; unitTo[2] = toX; unitTo[3] = toY
        onto.setPolyToPoly(unitFrom, 0, unitTo, 0, 2)
        paint?.setLocalMatrix(onto)

        threadGlow.shader = paint
        threadGlow.alpha = alpha / 6
        threadGlow.strokeWidth = weight[i] * 6f * density
        canvas.drawPath(stroke, threadGlow)
        threadGlow.strokeWidth = weight[i] * 3f * density
        canvas.drawPath(stroke, threadGlow)

        thread.shader = paint
        thread.alpha = alpha
        thread.strokeWidth = weight[i] * 1.5f * density
        canvas.drawPath(stroke, thread)

        // One light running along it, which is what says which way the thread is flowing.
        val r = riding[i]
        if (r in 0f..1f && r <= reach) {
            val wave = sin(phaseOf[i] + r * waves[i] * TAU - age * drift[i]) *
                sway[i] * density * bell(r)
            val x = quad(fromX, cx, toX, r) + px * wave
            val y = quad(fromY, cy, toY, r) + py * wave
            val fade = bell(r) * 0.7f + 0.3f
            val pa = (presence * fade * 235f).toInt().coerceIn(0, 235)
            val size = (1.6f + weight[i]) * density
            pulseGlow.color = withAlpha(blend(colour, RING, r), pa / 4)
            canvas.drawCircle(x, y, size * 2.4f, pulseGlow)
            canvas.drawCircle(x, y, size * 1.4f, pulseGlow)
            pulse.color = withAlpha(0xFFFFFFFF.toInt(), pa)
            canvas.drawCircle(x, y, size * 0.7f, pulse)
        }
    }

    private fun drawRing(canvas: Canvas) {
        val rr = ringRadius * (0.55f + presence * 0.45f)
        val a = (presence * 255).toInt()
        halo.color = withAlpha(RING, a / 6)
        halo.strokeWidth = 11 * density
        canvas.drawCircle(ringX, ringY, rr, halo)
        halo.strokeWidth = 6 * density
        canvas.drawCircle(ringX, ringY, rr, halo)
        glass.color = withAlpha(RING, (presence * 46).toInt())
        canvas.drawCircle(ringX, ringY, rr - rim.strokeWidth, glass)
        rim.color = withAlpha(RING, a)
        canvas.drawCircle(ringX, ringY, rr - rim.strokeWidth, rim)
        pip.color = withAlpha(0xFFFFF7E8.toInt(), a)
        canvas.drawCircle(ringX, ringY, 3 * density, pip)
    }

    // ── setup ────────────────────────────────────────────────────────────────────────

    private fun reseed() {
        for (i in 0 until STRANDS) {
            // Spread around the ring rather than scattered at random: strands that share a
            // landing point read as one thick line, and the ring should be held from
            // several sides.
            ends[i] = HALF_PI + (i - (STRANDS - 1) / 2f) * (TAU / (STRANDS * 2.2f)) +
                (random.nextFloat() - 0.5f) * 0.25f
            sway[i] = SWAY_MIN + random.nextFloat() * (SWAY_MAX - SWAY_MIN)
            // Under one full wave along the thread: more than that and a strand doubles
            // back on itself, and several of them together read as a tangle.
            waves[i] = 0.35f + random.nextFloat() * 0.4f
            drift[i] = DRIFT_MIN + random.nextFloat() * (DRIFT_MAX - DRIFT_MIN)
            // Spread around the circle rather than dropped anywhere on it, so the strands
            // travel beside one another instead of crossing.
            phaseOf[i] = i * (TAU / STRANDS) + random.nextFloat() * 0.4f
            hue[i] = palette[i % palette.size]
            weight[i] = 0.55f + random.nextFloat() * 0.75f
            riding[i] = random.nextFloat()
            gradients[i] = LinearGradient(
                0f, 0f, 0f, 1f,
                intArrayOf(hue[i], RING),
                floatArrayOf(0.15f, 0.95f),
                Shader.TileMode.CLAMP
            )
        }
    }

    /**
     * The colours the mark is made of, a few of them.
     *
     * The icon is drawn small and read for the colours it actually uses, so the thread is
     * the mark's own light. Only the distinct ones are kept: an icon is mostly one or two
     * colours, and a palette of near-identical entries would draw every strand alike.
     */
    private fun sampleIconColours(context: Context): IntArray {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
            ?: return intArrayOf(RING)
        val n = 24
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        // The vector is scaled to sit inside the 108 viewport; draw the whole thing.
        drawable.setBounds(-n / 2, -n / 2, (n * 1.5f).toInt(), (n * 1.5f).toInt())
        drawable.draw(c)
        val found = ArrayList<Int>(8)
        for (y in 0 until n) for (x in 0 until n) {
            val px = bitmap.getPixel(x, y)
            if (Color.alpha(px) < 120) continue
            val opaque = px or 0xFF000000.toInt()
            if (found.none { near(it, opaque) }) found += opaque
            if (found.size >= 6) break
        }
        bitmap.recycle()
        return if (found.isEmpty()) intArrayOf(RING) else found.toIntArray()
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAU = 6.2831855f
        const val HALF_PI = 1.5707964f
        const val RING = 0xFF4C9AFF.toInt()

        const val FORM_MS = 340
        const val DISSOLVE_MS = 260

        /** How many filaments the thread is made of. Few enough to read as lines. */
        const val STRANDS = 7
        /** How finely each is sampled along its length. */
        const val SAMPLES = 26
        /** How far a strand's wave carries it off its path, dp, over a full-length reach. */
        const val SWAY_MIN = 4f
        const val SWAY_MAX = 13f
        /** The reach that sway is quoted for; a shorter thread sways proportionally less. */
        const val REFERENCE_SPAN = 190f
        /** How fast the wave travels along it, radians a second. */
        const val DRIFT_MIN = 1.6f
        const val DRIFT_MAX = 3.2f
        /** How fast a light travels the length of a strand, in journeys a second. */
        const val PULSE_MIN = 0.45f
        /** How much the whole thread bows to one side. */
        const val BOW = 0.13f
        /** How quickly the strands brighten and dim, radians a second. */
        const val BREATH = 2.4f

        fun ease(x: Float): Float {
            val u = x.coerceIn(0f, 1f)
            return u * u * (3 - 2 * u)
        }

        /** 0 at the ends, 1 in the middle: a wave anchored where the thread is tied. */
        fun bell(x: Float): Float {
            val u = x.coerceIn(0f, 1f)
            return sin(u * Math.PI).toFloat()
        }

        fun quad(a: Float, b: Float, c: Float, t: Float): Float {
            val u = 1 - t
            return u * u * a + 2 * u * t * b + t * t * c
        }

        /** A unit vector at a right angle to the line from one point to another. */
        fun perpendicular(x0: Float, y0: Float, x1: Float, y1: Float): Pair<Float, Float> {
            val dx = x1 - x0; val dy = y1 - y0
            val len = hypot(dx, dy).coerceAtLeast(1f)
            return -dy / len to dx / len
        }

        fun near(a: Int, b: Int): Boolean =
            kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) +
                kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 90

        /** One colour on the way to another, for a thread that changes along its length. */
        fun blend(from: Int, to: Int, f: Float): Int {
            val u = f.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(from) + (Color.red(to) - Color.red(from)) * u).toInt(),
                (Color.green(from) + (Color.green(to) - Color.green(from)) * u).toInt(),
                (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * u).toInt(),
            )
        }

        fun withAlpha(colour: Int, a: Int): Int =
            (colour and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    }
}
