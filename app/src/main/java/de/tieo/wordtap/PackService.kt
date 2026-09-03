package de.tieo.wordtap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Downloads a Wiktextract dump and turns it into a dictionary pack, in the foreground so the
 * work survives the app being put away.
 *
 * It is one job at a time: building a language reads a gigabyte of JSON, and two at once
 * would only make both slower.
 */
class PackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** What a build is doing right now, for whoever is watching it. */
    sealed interface State {
        data object Idle : State
        data class Working(
            val wordLanguage: String,
            val bytesRead: Long,
            val totalBytes: Long,
            val entries: Int
        ) : State

        data class Done(val wordLanguage: String, val entries: Int) : State
        data class Failed(val wordLanguage: String, val reason: String) : State
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                stop()
                return START_NOT_STICKY
            }
            ACTION_BUILD -> {
                val word = intent.getStringExtra(EXTRA_WORD_LANGUAGE) ?: return stopped()
                val gloss = intent.getStringExtra(EXTRA_GLOSS_LANGUAGE) ?: return stopped()
                if (job?.isActive == true) return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, notification(word, 0, 0))
                job = scope.launch { build(gloss, word) }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopped(): Int {
        stop()
        return START_NOT_STICKY
    }

    private suspend fun build(glossLanguage: String, wordLanguage: String) {
        val url = PackSource.dumpUrl(glossLanguage, wordLanguage)
        state.value = State.Working(wordLanguage, 0, 0, 0)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept-Encoding", "gzip")
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                fail(wordLanguage, getString(R.string.pack_missing, name(wordLanguage)))
                return
            }
            // Progress is counted in the bytes that come off the network, which is what the
            // server told us the size of, not the far larger JSON they unpack into.
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            val counting = CountingStream(connection.inputStream)
            val stream: InputStream =
                if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(counting)
                } else {
                    counting
                }

            val target = Dictionary.file(this, glossLanguage, wordLanguage)
            var lastShown = 0L
            val entries = PackBuilder.build(
                input = stream,
                target = target,
                glossLanguage = glossLanguage,
                wordLanguage = wordLanguage,
                totalBytes = total,
                counted = { counting.count },
                cancelled = { job?.isCancelled == true },
                onProgress = { progress ->
                    state.value = State.Working(
                        wordLanguage,
                        progress.bytesRead,
                        progress.totalBytes,
                        progress.entries
                    )
                    // The notification is redrawn rarely: a build runs for minutes and the
                    // system throttles an app that posts more often than it can be read.
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 1000) {
                        lastShown = now
                        notify(notification(wordLanguage, progress.bytesRead, progress.totalBytes))
                    }
                }
            )
            state.value = State.Done(wordLanguage, entries)
        } catch (e: Throwable) {
            if (job?.isCancelled == true) {
                state.value = State.Idle
            } else {
                Log.w("WordTap", "pack build failed", e)
                fail(wordLanguage, e.message ?: e.javaClass.simpleName)
            }
        } finally {
            connection?.disconnect()
            stop()
        }
    }

    private fun fail(wordLanguage: String, reason: String) {
        state.value = State.Failed(wordLanguage, reason)
    }

    private fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun notification(wordLanguage: String, read: Long, total: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.pack_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, PackService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.pack_building, name(wordLanguage)))
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
        if (total > 0) {
            builder.setProgress(100, ((read * 100) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notify(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

    private fun name(tag: String): String =
        Locale.forLanguageTag(tag).displayLanguage.ifEmpty { tag }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Counts what has actually come off the network, under whatever decodes it. */
    private class CountingStream(stream: InputStream) : FilterInputStream(stream) {
        @Volatile
        var count: Long = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        private const val ACTION_BUILD = "de.tieo.wordtap.BUILD_PACK"
        private const val ACTION_CANCEL = "de.tieo.wordtap.CANCEL_PACK"
        private const val EXTRA_WORD_LANGUAGE = "wordLanguage"
        private const val EXTRA_GLOSS_LANGUAGE = "glossLanguage"
        private const val CHANNEL_ID = "packs"
        private const val NOTIFICATION_ID = 2

        /** Shared so the screen can show what the service is doing without binding to it. */
        val state: MutableStateFlow<State> = MutableStateFlow(State.Idle)

        fun states(): StateFlow<State> = state

        fun start(context: Context, glossLanguage: String, wordLanguage: String) {
            val intent = Intent(context, PackService::class.java)
                .setAction(ACTION_BUILD)
                .putExtra(EXTRA_GLOSS_LANGUAGE, glossLanguage)
                .putExtra(EXTRA_WORD_LANGUAGE, wordLanguage)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, PackService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
