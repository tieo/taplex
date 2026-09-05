package de.tieo.taplex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the last lookup saw, kept so it can be read off the device as a file.
 *
 * The overlay is a window of its own over whatever app is in front, and a screenshot does
 * not always contain it, so a picture is not a reliable way to check what Taplex found.
 * A log line is not either: logcat clips a line at a few kilobytes and a screenful of
 * words passes that easily.
 */
object DebugState {

    @Volatile
    private var last: JSONObject = JSONObject()

    fun lookup(found: Recognised, reported: Boolean) {
        last = JSONObject().apply {
            put("source", if (reported) "nodes" else "screenshot")
            put("wordCount", found.words.size)
            put("prose", found.prose().take(300))
            put(
                "words",
                JSONArray().apply {
                    for (word in found.words.take(200)) {
                        put(
                            JSONObject()
                                .put("text", word.text)
                                .put("left", word.bounds.left)
                                .put("top", word.bounds.top)
                                .put("right", word.bounds.right)
                                .put("bottom", word.bounds.bottom)
                                .put("line", word.line)
                        )
                    }
                }
            )
        }
    }

    /** The last few arming decisions, since the circle not appearing leaves no other trace. */
    private val follows = ArrayDeque<String>()

    @Synchronized
    fun followed(fromEvent: String?, front: String?, wanted: Set<String>) {
        follows.addLast("event=" + fromEvent + " front=" + front + " wanted=" + wanted)
        while (follows.size > 40) follows.removeFirst()
    }

    @Synchronized
    fun json(): String =
        JSONObject(last.toString())
            .put("follows", JSONArray(follows.toList()))
            .toString(2)
}

/**
 * Debug builds only: writes [DebugState] to a file `adb pull` can fetch, and starts a
 * lookup without anyone touching the screen, so the whole path can be exercised from a
 * shell.
 *
 *   adb shell am broadcast -a de.tieo.taplex.DEBUG_DUMP -p de.tieo.taplex
 *   adb pull /sdcard/Android/data/de.tieo.taplex/files/debug_state.json
 *   adb shell am broadcast -a de.tieo.taplex.DEBUG_LOOKUP -p de.tieo.taplex
 *
 * Exported on purpose: a receiver that is not exported takes a shell broadcast on the
 * emulator and is silently dropped on some real devices. All either action can do is write
 * app-private state to app-private storage, or start the lookup the accessibility button
 * starts anyway.
 */
class DebugBridge(
    private val onLookup: () -> Unit,
    private val onHoverPackage: (String) -> Unit = {}
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DUMP -> {
                    val dir = context.getExternalFilesDir(null) ?: return
                    File(dir, "debug_state.json").writeText(DebugState.json())
                    // The journal is the useful half: what happened, in order, including
                    // the runs where nothing was looked up because nothing came up.
                    File(dir, "journal.log").writeText(
                        Journal.recent().joinToString("\n", postfix = "\n")
                    )
                }
                ACTION_LOOKUP -> onLookup()
                // The circle follows one app, and the app it was built for is not on an
                // emulator. This points it at whatever is being tested with instead.
                ACTION_HOVER -> {
                    val target = intent.getStringExtra("package") ?: return
                    Prefs(context).apply {
                        hoverPackages = target.split(",").map { it.trim() }.toSet()
                        hoverEnabled = true
                    }
                    onHoverPackage(target)
                }
            }
        }
    }

    fun register(context: Context) {
        if (!BuildConfig.DEBUG) return
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP)
            addAction(ACTION_LOOKUP)
            addAction(ACTION_HOVER)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregister(context: Context) {
        if (!BuildConfig.DEBUG) return
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object {
        const val ACTION_DUMP = "de.tieo.taplex.DEBUG_DUMP"
        const val ACTION_LOOKUP = "de.tieo.taplex.DEBUG_LOOKUP"
        const val ACTION_HOVER = "de.tieo.taplex.DEBUG_HOVER"
    }
}
