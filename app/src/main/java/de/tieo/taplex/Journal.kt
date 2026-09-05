package de.tieo.taplex

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app did, kept where it can be read afterwards.
 *
 * The overlay's failures are the kind nobody can catch in the act: a circle that is not
 * there, a lookup that answered nothing, a service that went away and came back. logcat
 * holds minutes and only while something is watching, so every line goes to a file in the
 * app's own storage as well, and the last of them are handed back with the debug dump.
 *
 * Lines say what happened and what it was decided from, never what anybody read: a word
 * looked up is a length, not the word.
 */
object Journal {

    private const val TAG = "Taplex"
    private const val KEEP = 400
    private const val MAX_BYTES = 256 * 1024

    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val recent = ArrayDeque<String>()
    private var file: File? = null

    /** Where the file lives. Called once, from the service and the app alike. */
    @Synchronized
    fun open(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "journal.log")
    }

    @Synchronized
    fun note(what: String) {
        val line = clock.format(Date()) + "  " + what
        recent.addLast(line)
        while (recent.size > KEEP) recent.removeFirst()
        Log.d(TAG, what)
        val target = file ?: return
        runCatching {
            // Rolled by hand rather than kept forever: this is the tail of a session, not
            // a record of the phone.
            if (target.length() > MAX_BYTES) target.writeText("")
            target.appendText(line + "\n")
        }
    }

    /** Anything that threw, with the reason but not a stack the size of the file. */
    @Synchronized
    fun failed(what: String, error: Throwable) {
        note(what + " failed: " + (error.message ?: error.javaClass.simpleName))
    }

    @Synchronized
    fun recent(): List<String> = recent.toList()
}
