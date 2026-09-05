package de.tieo.taplex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The circle you aim with, and the current of light that becomes it.
 *
 * The mark parked at the edge is the app's own icon. When it is dragged it does not slide a
 * disc up the screen; it comes apart into its own pixels and those stream up the gap between
 * the finger and the word, gathering into a ring around the word. The particles are sampled
 * from the launcher icon itself, so the light flowing up the ether carries the icon's own
 * colours rather than a colour chosen for it.
 *
 * It lives in the layer that already covers the screen and takes no touch, so it is drawn
 * here rather than owning a window: a frame clock advances the current, the ring rides the
 * finger, and on release the whole thing falls back into the mark.
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
        maskFilter = BlurMaskFilter(9 * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val pip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sparkGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(4 * density, BlurMaskFilter.Blur.NORMAL)
    }

    // ── where things are ─────────────────────────────────────────────────────────────
    private var fingerX = 0f
    private var fingerY = 0f
    private var ringX = 0f
    private var ringY = 0f
    private var ringRadius = 0f

    /** 0 while parked, 1 while a ring is fully formed; the animators run it between. */
    private var presence = 0f

    /** Called once the ring has fallen all the way back into the mark. */
    var onDissolved: (() -> Unit)? = null

    private enum class Phase { GONE, FORMING, LIVE, DISSOLVING }
    private var phase = Phase.GONE
    private var phaseStart = 0L
    private var homeX = 0f
    private var homeY = 0f

    // ── the particles: the icon, taken to pieces ─────────────────────────────────────
    private val count: Int
    private val colour: IntArray
    private val homeDx: FloatArray   // where this pixel sits in the icon, from its centre
    private val homeDy: FloatArray
    private val t: FloatArray        // 0 at the finger, 1 at the ring
    private val speed: FloatArray
    private val lane: FloatArray     // how far it wanders off the straight path
    private val lanePhase: FloatArray
    private val angle: FloatArray    // where on the ring it is heading
    private val grain: FloatArray    // its size

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
        val sampled = sampleIcon(context)
        count = sampled.size
        colour = IntArray(count)
        homeDx = FloatArray(count)
        homeDy = FloatArray(count)
        t = FloatArray(count)
        speed = FloatArray(count)
        lane = FloatArray(count)
        lanePhase = FloatArray(count)
        angle = FloatArray(count)
        grain = FloatArray(count)
        sampled.forEachIndexed { i, p ->
            colour[i] = p.colour
            homeDx[i] = p.dx
            homeDy[i] = p.dy
        }
        reseed(spread = true)
    }

    // ── the drag tells it these ──────────────────────────────────────────────────────

    /** The current between the finger and the word starts flowing up out of the mark. */
    fun form(fingerX: Float, fingerY: Float, ringX: Float, ringY: Float, radius: Float) {
        this.fingerX = fingerX; this.fingerY = fingerY
        this.ringX = ringX; this.ringY = ringY; this.ringRadius = radius
        if (phase == Phase.LIVE || phase == Phase.FORMING) return
        reseed(spread = false)
        phase = Phase.FORMING
        phaseStart = now()
        start()
    }

    /** Every frame of the drag: the ring rides the finger and the current follows. */
    fun follow(fingerX: Float, fingerY: Float, ringX: Float, ringY: Float, radius: Float) {
        this.fingerX = fingerX; this.fingerY = fingerY
        this.ringX = ringX; this.ringY = ringY; this.ringRadius = radius
    }

    /** The finger is gone: the ring falls back into the mark at [homeX],[homeY]. */
    fun dissolve(homeX: Float, homeY: Float) {
        if (phase == Phase.GONE || phase == Phase.DISSOLVING) return
        this.homeX = homeX; this.homeY = homeY
        phase = Phase.DISSOLVING
        phaseStart = now()
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
        for (i in 0 until count) {
            t[i] += speed[i] * dt
            if (t[i] >= 1f) {
                // A pixel that reached the ring is sent up from the finger again, so the
                // current never runs dry while the finger is down.
                t[i] -= 1f
                speed[i] = SPEED_MIN + Math.random().toFloat() * (SPEED_MAX - SPEED_MIN)
                angle[i] = Math.random().toFloat() * TAU
                lane[i] = (Math.random().toFloat() - 0.5f) * LANE * density
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (phase == Phase.GONE || presence <= 0f) return

        // Where the current is flowing: up from the finger while dragging, back down into
        // the mark while dissolving.
        val fromX: Float; val fromY: Float
        if (phase == Phase.DISSOLVING) { fromX = homeX; fromY = homeY } else { fromX = fingerX; fromY = fingerY }
        val toBend = perpendicular(fromX, fromY, ringX, ringY)
        val span = hypot(ringX - fromX, ringY - fromY).coerceAtLeast(1f)

        for (i in 0 until count) {
            val tt = t[i]
            val tx = ringX + cos(angle[i]) * ringRadius
            val ty = ringY + sin(angle[i]) * ringRadius
            // A curved path, control point pushed to the side by a share of the whole span,
            // plus a wander that is widest in the middle of the journey and nothing at the
            // ends.
            val cx = (fromX + tx) / 2f + toBend.first * span * BEND
            val cy = (fromY + ty) / 2f + toBend.second * span * BEND
            val bend = sin(lanePhase[i] + tt * 6.2832f) * lane[i] * bell(tt)
            var x = quad(fromX, cx, tx, tt) + toBend.first * bend
            var y = quad(fromY, cy, ty, tt) + toBend.second * bend
            // The first of the journey wears the icon's own shape: each mote leaves the
            // finger where its pixel sat in the mark, and lets go of it as it rises, so the
            // base of the current reads as the mark coming apart rather than a plain jet.
            if (tt < 0.3f) {
                val holds = 1f - tt / 0.3f
                x += homeDx[i] * holds
                y += homeDy[i] * holds
            }
            // The last of the journey coils onto the ring rather than arriving straight.
            if (tt > 0.82f) {
                val coil = (tt - 0.82f) / 0.18f
                val a = angle[i] + coil * 1.4f
                x = x * (1 - coil) + (ringX + cos(a) * ringRadius) * coil
                y = y * (1 - coil) + (ringY + sin(a) * ringRadius) * coil
            }
            val fade = bell(tt) * 0.55f + 0.45f
            val a = (presence * fade * 235f).toInt().coerceIn(0, 235)
            val r = grain[i] * (0.7f + bell(tt) * 0.6f)
            spark.color = withAlpha(colour[i], a)
            sparkGlow.color = withAlpha(colour[i], a / 3)
            canvas.drawCircle(x, y, r * 1.9f, sparkGlow)
            canvas.drawCircle(x, y, r, spark)
        }

        drawRing(canvas)
    }

    private fun drawRing(canvas: Canvas) {
        val rr = ringRadius * (0.55f + presence * 0.45f)
        val a = (presence * 255).toInt()
        halo.color = withAlpha(RING, a / 3)
        canvas.drawCircle(ringX, ringY, rr, halo)
        glass.color = withAlpha(RING, (presence * 46).toInt())
        canvas.drawCircle(ringX, ringY, rr - rim.strokeWidth, glass)
        rim.color = withAlpha(RING, a)
        canvas.drawCircle(ringX, ringY, rr - rim.strokeWidth, rim)
        pip.color = withAlpha(0xFFFFF7E8.toInt(), a)
        canvas.drawCircle(ringX, ringY, 3 * density, pip)
    }

    // ── setup ────────────────────────────────────────────────────────────────────────

    private fun reseed(spread: Boolean) {
        for (i in 0 until count) {
            t[i] = if (spread) Math.random().toFloat() else Math.random().toFloat() * 0.15f
            speed[i] = SPEED_MIN + Math.random().toFloat() * (SPEED_MAX - SPEED_MIN)
            lane[i] = (Math.random().toFloat() - 0.5f) * LANE * density
            lanePhase[i] = Math.random().toFloat() * TAU
            angle[i] = Math.random().toFloat() * TAU
            grain[i] = (1.1f + Math.random().toFloat() * 1.6f) * density
        }
    }

    private class Dot(val dx: Float, val dy: Float, val colour: Int)

    /** The launcher mark, drawn small and read pixel by pixel into a handful of motes. */
    private fun sampleIcon(context: Context): List<Dot> {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
            ?: return listOf(Dot(0f, 0f, RING))
        val n = 34
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        // The vector is scaled to sit inside the 108 viewport; draw the whole thing.
        drawable.setBounds(-n / 2, -n / 2, (n * 1.5f).toInt(), (n * 1.5f).toInt())
        drawable.draw(c)
        val dots = ArrayList<Dot>(200)
        val step = (BUBBLE_DP * density) / n
        for (y in 0 until n) for (x in 0 until n) {
            val px = bitmap.getPixel(x, y)
            if (Color.alpha(px) < 60) continue
            dots += Dot((x - n / 2f) * step, (y - n / 2f) * step, px or 0xFF000000.toInt())
        }
        bitmap.recycle()
        return if (dots.isEmpty()) listOf(Dot(0f, 0f, RING)) else dots
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val BUBBLE_DP = 40f
        const val TAU = 6.2831855f
        const val RING = 0xFF4C9AFF.toInt()

        const val FORM_MS = 340
        const val DISSOLVE_MS = 240

        const val SPEED_MIN = 0.9f   // journeys per second
        const val SPEED_MAX = 1.9f
        const val LANE = 26f         // how far a mote wanders, dp
        const val BEND = 0.16f       // how much the whole current bows to one side

        fun ease(x: Float): Float {
            val u = x.coerceIn(0f, 1f)
            return u * u * (3 - 2 * u)
        }

        /** 0 at the ends, 1 in the middle: a wander that starts and finishes tidy. */
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

        fun withAlpha(colour: Int, a: Int): Int =
            (colour and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    }
}
