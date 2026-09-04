package de.tieo.taplex

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * The lookup path that records nothing.
 *
 * Nothing is captured between lookups and no screen capture session exists: the words come
 * from the accessibility node tree, which the apps on screen fill in themselves, and only
 * when there is no usable text there does this take a single screenshot and hand it to the
 * recogniser. The trigger is the system accessibility button, so there is no bubble of ours
 * to hide from a capture either.
 *
 * The MediaProjection path in [CaptureService] stays for when this service is off.
 */
class TaplexAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var overlay: OverlayController? = null
    private var hover: HoverController? = null
    private val debug = DebugBridge(
        onLookup = { lookUp() },
        onHoverPackage = { hoverController().arm() }
    )

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = lookUp()
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        debug.register(this)
        running = this
        // The app someone is in when they switch this on never changes window, so waiting
        // for a window event would leave the circle absent from exactly the conversation
        // they turned it on for.
        follow()
    }

    /**
     * The circle follows one app: it comes up when that app is in front and goes away when
     * it is not, so a bubble is never sitting over anything else.
     *
     * What counts as being in front is the window the system is actually treating as
     * active, not the package the event names. A keyboard, a toast, a system dialog and
     * Taplex's own overlay all raise window events of their own, and taking those at face
     * value pulls the circle away in the middle of a conversation that never left.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        follow()
    }

    /** Puts the circle up if the app it belongs to is in front, and takes it away if not. */
    private fun follow() {
        val prefs = Prefs(this)
        if (!prefs.hoverEnabled || !Settings.canDrawOverlays(this)) {
            hover?.disarm()
            return
        }
        val front = frontApp()
        if (BuildConfig.DEBUG) {
            Log.d("Taplex", "front=" + front + " wanted=" + prefs.hoverPackage)
        }
        if (front == null || front == packageName) return
        if (front == prefs.hoverPackage) hoverController().arm() else hover?.disarm()
    }

    /**
     * The app whose window is active, which is the one being read. Anything of the system's
     * own that happens to be showing is not an app coming to the front.
     */
    private fun frontApp(): String? {
        val active = rootInActiveWindow?.packageName?.toString()
        if (active != null && active !in SYSTEM_WINDOWS) return active
        return windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }
            ?.root
            ?.packageName
            ?.toString()
    }

    private fun hoverController(): HoverController =
        hover ?: HoverController(
            context = this,
            windowManager = windowManager,
            readWords = { wordsOnScreen() }
        ).also { hover = it }

    /**
     * What is readable on screen right now, by the same rule the modal layer uses: the
     * words the apps reported themselves where those carry the screen, and a recognised
     * screenshot where they do not.
     *
     * A view reports where each of its characters sits only if it implements the request.
     * A browser answers with whole paragraphs and no character positions, which is a
     * screenful of text and no word anyone can point at, so that screen is read as a
     * picture instead.
     */
    private suspend fun wordsOnScreen(): Recognised {
        val reading = NodeWords.read(rootInActiveWindow, packageName, screenBounds())
        val reported = reading.found.words.size >= MIN_REPORTED_WORDS &&
            reading.resolvedCharacters >= reading.unresolvedCharacters
        if (reported) return reading.found
        val frame = frame() ?: return reading.found
        val recognised = runCatching { Ocr.run(frame) }.getOrNull()
        frame.recycle()
        return recognised ?: reading.found
    }

    /** The screen as a picture, or null when the system will not hand one over. */
    private suspend fun frame(): Bitmap? = suspendCancellableCoroutine { waiting ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            waiting.resume(null)
            return@suspendCancellableCoroutine
        }
        screenshot { waiting.resume(it) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = null
        hover?.close()
        hover = null
        overlay?.dismiss()
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        debug.unregister(this)
        return super.onUnbind(intent)
    }

    /**
     * Reads what is on screen and puts the word boxes over it. A second press while the
     * overlay is up closes it, so the button toggles rather than stacking overlays.
     */
    fun lookUp() {
        overlay?.let {
            it.dismiss()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.needs_overlay))
            return
        }

        // Read the words before anything of ours is on screen, then freeze what the screen
        // was showing at that moment. Both describe the same instant, so the boxes and the
        // picture under them agree however the app behind rearranges itself afterwards.
        val reading = NodeWords.read(rootInActiveWindow, packageName, screenBounds())
        Log.d(
            "Taplex",
            "node words=" + reading.found.words.size +
                " resolved=" + reading.resolvedCharacters +
                " unresolved=" + reading.unresolvedCharacters
        )
        val reported = reading.found.words.size >= MIN_REPORTED_WORDS &&
            reading.resolvedCharacters >= reading.unresolvedCharacters

        screenshot { frame ->
            when {
                reported -> show(
                    OverlayController.Source.Reported(reading.found, screenWidth(), frame)
                )
                // Games, images, video and PDFs rendered as bitmaps report no text at all,
                // a screenful of buttons reports too little to be worth a modal layer, and
                // a browser reports its text without saying where each word sits. The frame
                // is read by the recogniser instead: still no capture session, still
                // nothing running between lookups.
                frame != null -> show(OverlayController.Source.Frame(frame))
                else -> toast(getString(R.string.no_text_found))
            }
        }
    }

    private fun show(source: OverlayController.Source) {
        overlay = OverlayController(this, windowManager, source) { overlay = null }
            .also { it.show() }
    }

    private fun screenshot(onFrame: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onFrame(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        buffer.close()
                    }
                    onFrame(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w("Taplex", "screenshot failed, code=$errorCode")
                    onFrame(null)
                }
            }
        )
    }

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(windowManager.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            Rect(0, 0, windowManager.defaultDisplay.width, windowManager.defaultDisplay.height)
        }

    private fun screenWidth(): Int = screenBounds().width()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        /**
         * Below this the node tree is carrying chrome rather than something to read, and
         * the screenshot path sees more than it does.
         */
        private const val MIN_REPORTED_WORDS = 3

        /** Windows that belong to the system rather than to whatever app is being read. */
        private val SYSTEM_WINDOWS = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin"
        )

        /**
         * The connected service, or null while the user has it turned off. Anything in the
         * app that can start a lookup goes through this rather than through a broadcast:
         * the tile, the button callback and the service all live in one process.
         */
        @Volatile
        var running: TaplexAccessibilityService? = null
            private set
    }
}
