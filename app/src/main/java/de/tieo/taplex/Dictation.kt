package de.tieo.taplex

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Saying the word instead of typing it.
 *
 * A phrase someone wants said in the language they are learning is the one thing here they
 * cannot look up by pointing at it: it exists only in their head. Typing it on a keyboard
 * that has just covered half the conversation is the slowest way to ask, so the field takes
 * dictation.
 *
 * The recogniser on the phone is used directly rather than through the system's dictation
 * screen: that screen is an activity, and an activity started from an overlay puts the
 * conversation away to ask its question. This listens inside the panel that is already
 * open, and shows the words as they are heard. Where the phone can do it without the
 * network it is asked to, since a lookup that works on a train is the point of the packs.
 */
class Dictation(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Words as they are heard, replaced as the guess improves. */
    var onPartial: (String) -> Unit = {}

    /** The finished phrase. */
    var onFinal: (String) -> Unit = {}

    /** Listening started, stopped, or failed, so the panel can say which. */
    var onState: (Boolean) -> Unit = {}

    /** Whether there is a recogniser and permission to use it. */
    fun canListen(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) && hasPermission()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts listening for a phrase in [language], which is the reader's own language: they
     * are saying what they want to be able to say, not the words they are learning.
     */
    fun start(language: String) {
        if (listening) {
            stop()
            return
        }
        onDevice = true
        listen(language)
    }

    /** Whether the recogniser now in hand is the one that runs on the phone. */
    private var onDevice = true

    private fun listen(language: String) {
        if (!hasPermission()) {
            Journal.note("dictation: no permission to listen")
            return
        }
        val client = build() ?: run {
            Journal.note("dictation: no recogniser on this phone")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // A full tag, not a bare code. The recogniser on the phone answers "language
            // not supported" to "en" and understands "en-US"; where the language wanted is
            // the one the phone is set to, the phone's own tag is the one it certainly has.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tagFor(language))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        client.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                onState(true)
            }

            override fun onPartialResults(results: Bundle?) {
                heard(results)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                onState(false)
                heard(results)?.let(onFinal)
            }

            override fun onError(error: Int) {
                listening = false
                onState(false)
                Journal.note("dictation: stopped, reason $error")
                // The recogniser that runs on the phone does not hold every language.
                // Where it says so, the question is put to the general one rather than
                // answered with nothing.
                if (onDevice && error in UNSUPPORTED) {
                    onDevice = false
                    close()
                    Journal.note("dictation: asking the other recogniser instead")
                    listen(language)
                }
            }

            override fun onEndOfSpeech() {
                listening = false
                onState(false)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching { client.startListening(intent) }
            .onFailure { Journal.failed("listening", it) }
    }

    fun stop() {
        listening = false
        onState(false)
        runCatching { recognizer?.stopListening() }
    }

    fun close() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }

    /**
     * The recogniser to listen with: the one that runs on the phone where there is one,
     * since it answers without the network and without sending what was said anywhere, and
     * the ordinary one otherwise.
     */
    private fun build(): SpeechRecognizer? {
        recognizer?.let { return it }
        val made = runCatching {
            if (onDevice &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()
        recognizer = made
        return made
    }

    /**
     * The tag to listen in. A language that is the phone's own is asked for by the phone's
     * whole tag, region and all, since that is the one it is set up for.
     */
    private fun tagFor(language: String): String {
        val here = Locale.getDefault()
        return if (here.language.equals(language, ignoreCase = true)) {
            here.toLanguageTag()
        } else {
            language
        }
    }

    private companion object {
        /** What a recogniser says when it does not hold the language it was asked for. */
        val UNSUPPORTED = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }

    private fun heard(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
}
