package de.tieo.taplex

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Where a dictionary comes from: kaikki.org, which publishes Wiktionary already parsed.
 *
 * kaikki has one set of files per Wiktionary edition, and the edition decides what language
 * the explanations are in: the English edition explains every language in English, the
 * German edition in German. Inside an edition there is one file per language of words, in a
 * folder named after that language as that edition writes it, which is exactly what a
 * [Locale] display name is.
 */
object PackSource {

    private const val HOST = "https://kaikki.org"

    /** A language that can be downloaded, with the size of its download. */
    data class Available(val code: String, val name: String, val url: String)

    /**
     * The address of the dump holding [wordLanguage] explained in [glossLanguage].
     *
     * The English edition lives at a different path from every other one, which is the only
     * irregularity in the scheme.
     */
    fun dumpUrl(glossLanguage: String, wordLanguage: String): String {
        val gloss = Locale.forLanguageTag(glossLanguage)
        val folder = Locale.forLanguageTag(wordLanguage).getDisplayLanguage(gloss)
        val encoded = folder.replace(" ", "%20")
        return if (glossLanguage == "en") {
            "$HOST/dictionary/$encoded/kaikki.org-dictionary-$encoded.jsonl"
        } else {
            "$HOST/${glossLanguage}wiktionary/$encoded/kaikki.org-dictionary-$encoded.jsonl"
        }
    }

    /**
     * The languages worth offering for a phone set to [glossLanguage]: the ones whose dump
     * that edition actually has. Asking the server about each language would be hundreds of
     * requests, so the list is every language Android knows a name for, and whether a
     * particular dump exists is settled by [size] when one is picked.
     */
    fun languages(glossLanguage: String): List<Available> {
        val gloss = Locale.forLanguageTag(glossLanguage)
        return Locale.getISOLanguages()
            .map { code ->
                Available(
                    code = code,
                    name = Locale.forLanguageTag(code).getDisplayLanguage(gloss),
                    url = dumpUrl(glossLanguage, code)
                )
            }
            .filter { it.name.isNotEmpty() && !it.name.equals(it.code, ignoreCase = true) }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    /**
     * The languages out of [all] whose name answers [query], the ones starting with it
     * first, so a typed prefix reaches the language before a name that merely contains it.
     */
    fun matching(all: List<Available>, query: String): List<Available> {
        val needle = query.trim()
        if (needle.isEmpty()) return all
        val (starts, contains) = all
            .filter { it.name.contains(needle, ignoreCase = true) }
            .partition { it.name.startsWith(needle, ignoreCase = true) }
        return starts + contains
    }

    /**
     * The size of a dump as it will be transferred, or null when there is no such dump.
     * kaikki serves these gzipped, so this is a tenth of the JSON that arrives.
     */
    fun size(url: String): Long? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            setRequestProperty("Accept-Encoding", "gzip")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.getHeaderField("Content-Length")?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
