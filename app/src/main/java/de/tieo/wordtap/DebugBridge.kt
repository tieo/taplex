package de.tieo.wordtap

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
 * not always contain it, so a picture is not a reliable way to check what WordTap found.
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
                        )
                    }
                }
            )
        }
    }

    fun json(): String = last.toString(2)
}

/**
 * Debug builds only: writes [DebugState] to a file `adb pull` can fetch, and starts a
 * lookup without anyone touching the screen, so the whole path can be exercised from a
 * shell.
 *
 *   adb shell am broadcast -a de.tieo.wordtap.DEBUG_DUMP -p de.tieo.wordtap
 *   adb pull /sdcard/Android/data/de.tieo.wordtap/files/debug_state.json
 *   adb shell am broadcast -a de.tieo.wordtap.DEBUG_LOOKUP -p de.tieo.wordtap
 *
 * Exported on purpose: a receiver that is not exported takes a shell broadcast on the
 * emulator and is silently dropped on some real devices. All either action can do is write
 * app-private state to app-private storage, or start the lookup the accessibility button
 * starts anyway.
 */
class DebugBridge(private val onLookup: () -> Unit) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DUMP -> {
                    val dir = context.getExternalFilesDir(null) ?: return
                    File(dir, "debug_state.json").writeText(DebugState.json())
                }
                ACTION_LOOKUP -> onLookup()
            }
        }
    }

    fun register(context: Context) {
        if (!BuildConfig.DEBUG) return
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP)
            addAction(ACTION_LOOKUP)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregister(context: Context) {
        if (!BuildConfig.DEBUG) return
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object {
        const val ACTION_DUMP = "de.tieo.wordtap.DEBUG_DUMP"
        const val ACTION_LOOKUP = "de.tieo.wordtap.DEBUG_LOOKUP"
    }
}
