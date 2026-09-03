package de.tieo.taplex

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.InflaterInputStream

/** One meaning of a word: what it means, how it is marked, and how it is used. */
data class Sense(val gloss: String, val examples: List<String>, val tags: List<String>)

/**
 * One dictionary entry: a word in one part of speech, with its meanings.
 *
 * [label] says what the tapped spelling is of this entry, "plural" or "past participle",
 * and is null when the tapped spelling is the entry's own lemma.
 */
data class Entry(
    val lemma: String,
    val pos: String?,
    val ipa: String?,
    val senses: List<Sense>,
    val label: String? = null
)

/**
 * A dictionary pack: the words of one language, explained in another, held in a SQLite file
 * built from Wiktionary by [PackBuilder] on the phone, or by `tools/build_pack.py` on a
 * computer, which write the same format.
 *
 * Packs are files rather than anything bundled in the APK: a single language runs to tens of
 * megabytes, only the languages someone actually reads are worth the space, and a pack can
 * be replaced with a newer build without touching the app.
 */
class Dictionary private constructor(
    private val db: SQLiteDatabase,
    val glossLanguage: String,
    val wordLanguage: String
) {

    /**
     * Every entry the given word leads to, ordered by how well each fits [line], the text
     * the word was read from. The lookup goes through the form index, so an inflected word
     * finds the entry of its lemma and is told which form it was.
     */
    fun look(word: String, line: String = ""): List<Entry> {
        val entries = candidates(word)
        if (entries.size < 2) return entries
        val (before, after) = Reading.neighbours(line, word)
        return Reading.rank(word, entries, partsOfSpeech(before), partsOfSpeech(after))
    }

    private fun candidates(word: String): List<Entry> {
        val key = word.lowercase().trim()
        if (key.isEmpty()) return emptyList()
        val entries = mutableListOf<Entry>()
        db.rawQuery(
            """
            SELECT e.lemma, e.pos, e.ipa, e.senses, f.label
            FROM forms f JOIN entries e ON e.id = f.entry_id
            WHERE f.key = ?
            LIMIT 12
            """.trimIndent(),
            arrayOf(key)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += Entry(
                    lemma = cursor.getString(0),
                    pos = cursor.getString(1),
                    ipa = cursor.getString(2),
                    senses = parseSenses(cursor.getBlob(3)),
                    label = cursor.getString(4)
                )
            }
        }
        return entries
    }

    /** What a word can be, across every entry it leads to. Empty for a word not in the pack. */
    fun partsOfSpeech(word: String?): Set<String> {
        val key = word?.lowercase()?.trim().orEmpty()
        if (key.isEmpty()) return emptySet()
        val found = mutableSetOf<String>()
        db.rawQuery(
            """
            SELECT DISTINCT e.pos
            FROM forms f JOIN entries e ON e.id = f.entry_id
            WHERE f.key = ?
            LIMIT 12
            """.trimIndent(),
            arrayOf(key)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { found += it.lowercase() }
            }
        }
        return found
    }

    fun close() = db.close()

    /** Senses are stored deflated, which is most of what keeps a pack to a phone's size. */
    private fun parseSenses(deflated: ByteArray): List<Sense> {
        val json = runCatching {
            InflaterInputStream(ByteArrayInputStream(deflated)).use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val gloss = item.optString("g")
            if (gloss.isEmpty()) return@mapNotNull null
            Sense(gloss, item.strings("x"), item.strings("t"))
        }
    }

    private fun org.json.JSONObject.strings(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
    }

    companion object {
        /**
         * Where packs are looked for. App-specific external storage, so a pack can be
         * copied onto the phone without any permission and survives an app update.
         */
        fun directory(context: Context): File =
            File(context.getExternalFilesDir(null), "dictionaries").also { it.mkdirs() }

        fun file(context: Context, glossLanguage: String, wordLanguage: String): File =
            File(directory(context), "$glossLanguage-$wordLanguage.db")

        /** The language pairs a pack exists for, as (explanation language, word language). */
        fun installed(context: Context): List<Pair<String, String>> =
            directory(context).listFiles().orEmpty()
                .mapNotNull { file ->
                    val parts = file.name.removeSuffix(".db").split("-")
                    if (file.name.endsWith(".db") && parts.size == 2) parts[0] to parts[1] else null
                }
                .sortedBy { it.second }

        /** Opens the pack explaining [wordLanguage] in [glossLanguage], or null if absent. */
        fun open(context: Context, glossLanguage: String, wordLanguage: String): Dictionary? {
            val file = file(context, glossLanguage, wordLanguage)
            if (!file.exists()) return null
            return runCatching {
                Dictionary(
                    SQLiteDatabase.openDatabase(
                        file.path,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    ),
                    glossLanguage,
                    wordLanguage
                )
            }.onFailure { Log.w("Taplex", "pack ${file.name} unreadable", it) }.getOrNull()
        }
    }
}
