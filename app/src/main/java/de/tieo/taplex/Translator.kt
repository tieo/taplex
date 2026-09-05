package de.tieo.taplex

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device translation. Models are roughly 30 MB per language and are fetched once,
 * after which everything runs offline.
 */
class WordTranslator {

    // The default threshold of 0.5 returns "und" for a screenful of mixed UI chrome, URLs and
    // numbers, which is exactly what a screenshot contains.
    private val languageId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.1f).build()
    )
    private val clients = mutableMapOf<String, Translator>()

    sealed interface Result {
        data class Ok(val text: String, val source: String, val target: String) : Result
        data class NeedsDownload(val source: String, val target: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Identifies the source language from [context] rather than from the tapped word alone:
     * a single word is often ambiguous, a screenful of text rarely is.
     */
    /**
     * Identifies the source language from [context] rather than from the tapped word alone:
     * a single word is often ambiguous, a screenful of text rarely is.
     *
     * [answerable] is the languages a dictionary exists for. A screen is never only the
     * text on it, and the chrome around a page is enough to make a confident guess at a
     * language nobody here can answer: a Spanish article read in a browser came back
     * Polish, which sent the lookup to a dictionary that does not exist while the Spanish
     * one sat installed. So the best candidate that can actually be answered wins, and the
     * outright best only when none of them can.
     */
    suspend fun identify(
        context: String,
        configured: String,
        answerable: Set<String> = emptySet()
    ): String? {
        if (configured != Prefs.AUTO) return configured
        val candidates = try {
            languageId.identifyPossibleLanguages(context).await()
        } catch (e: Exception) {
            Journal.failed("identifying the language", e)
            return null
        }
        val ranked = candidates
            .sortedByDescending { it.confidence }
            .mapNotNull { candidate ->
                if (candidate.languageTag == "und") null
                else TranslateLanguage.fromLanguageTag(candidate.languageTag)
            }
        val answered = ranked.firstOrNull { it in answerable }
        Journal.note(
            "language " + candidates.take(3).joinToString {
                it.languageTag + ":" + "%.2f".format(it.confidence)
            } + " -> " + (answered ?: ranked.firstOrNull())
        )
        return answered ?: ranked.firstOrNull()
    }

    suspend fun translate(word: String, source: String, target: String, allowDownload: Boolean): Result {
        if (source == target) return Result.Ok(word, source, target)
        val translator = clientFor(source, target) ?: return Result.Failed("unsupported language pair")
        return try {
            if (allowDownload) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } else {
                // Fails fast when the model is missing, so the caller can offer a download.
                translator.downloadModelIfNeeded(
                    DownloadConditions.Builder().requireWifi().build()
                ).await()
            }
            Result.Ok(translator.translate(word).await(), source, target)
        } catch (e: Exception) {
            Log.w(TAG, "translate $source>$target failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun clientFor(source: String, target: String): Translator? {
        val src = TranslateLanguage.fromLanguageTag(source) ?: return null
        val dst = TranslateLanguage.fromLanguageTag(target) ?: return null
        return clients.getOrPut("$src>$dst") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(dst)
                    .build()
            )
        }
    }

    companion object {
        private const val TAG = "Taplex"
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
        languageId.close()
    }
}
