package de.tieo.taplex

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.zip.Deflater

/**
 * Builds a dictionary pack on the device from a Wiktextract dump.
 *
 * The dump is read as it arrives and never stored: a language is a gigabyte of JSON, which
 * no phone should keep, while the pack it becomes is a tenth of that. Nothing here needs a
 * server of ours; the data comes from kaikki.org, and the shaping that used to happen on a
 * computer happens here instead.
 */
object PackBuilder {

    /** How far along a build is, in the terms the person waiting cares about. */
    data class Progress(val bytesRead: Long, val totalBytes: Long, val entries: Int)

    private const val MAX_SENSES = 12
    private const val MAX_GLOSS = 400
    private const val MAX_EXAMPLE = 200
    private const val SENSES_WITH_EXAMPLES = 4
    private const val BATCH = 2000

    /** Tags that describe the wiring rather than the word, and read as noise in a label. */
    private val LABEL_NOISE = setOf("form-of", "inflection-of", "alt-of")

    /** Marks a sense that carries no meaning of its own. */
    private val SKIP_TAGS = setOf("no-gloss", "misspelling")

    /**
     * Reads [input] to its end and writes the pack to [target], reporting progress as it
     * goes. [totalBytes] is what the download said it would be, only so progress can be
     * shown. Returns the number of entries written, or throws if the stream fails.
     *
     * The file is built beside [target] and moved into place at the end, so an interrupted
     * build never leaves something half written where the app would open it.
     */
    fun build(
        input: InputStream,
        target: File,
        glossLanguage: String,
        wordLanguage: String,
        totalBytes: Long,
        counted: () -> Long,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): Int {
        val partial = File(target.parentFile, target.name + ".part")
        partial.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(partial, null)
        try {
            schema(db)
            val entries = readAll(db, input, totalBytes, counted, cancelled, onProgress)
            finish(db, glossLanguage, wordLanguage, entries)
            db.close()
            target.delete()
            if (!partial.renameTo(target)) error("could not move the finished pack into place")
            return entries
        } catch (t: Throwable) {
            runCatching { db.close() }
            partial.delete()
            throw t
        }
    }

    private fun schema(db: SQLiteDatabase) {
        // Setting the journal mode answers with the mode the database ended up in, and
        // execSQL refuses any statement that returns a row.
        db.rawQuery("PRAGMA journal_mode = OFF", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA synchronous = OFF")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE entries (
                id     INTEGER PRIMARY KEY,
                lemma  TEXT NOT NULL,
                key    TEXT NOT NULL,
                pos    TEXT,
                ipa    TEXT,
                senses BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE forms (
                key      TEXT NOT NULL,
                entry_id INTEGER NOT NULL,
                label    TEXT,
                PRIMARY KEY (key, entry_id)
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE links (key TEXT, target TEXT, label TEXT)")
    }

    private fun readAll(
        db: SQLiteDatabase,
        input: InputStream,
        totalBytes: Long,
        counted: () -> Long,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): Int {
        // A dump is one JSON object per line; lenient mode reads them one after another
        // from the same stream, so no line is ever held as a string of its own.
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.isLenient = true

        var id = 0
        var written = 0
        db.beginTransaction()
        try {
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                if (cancelled()) throw InterruptedException("build cancelled")
                val entry = readEntry(reader) ?: continue
                val key = normalise(entry.word)
                for (link in entry.links) {
                    if (link.first != key) {
                        db.execSQL(
                            "INSERT INTO links (key, target, label) VALUES (?, ?, ?)",
                            arrayOf(key, link.first, link.second)
                        )
                    }
                }
                // An entry whose senses were all links elsewhere ("plural of casa") is
                // carried by those links, not by a row with nothing under the lemma.
                if (entry.senses.length() == 0) continue

                id++
                written++
                db.execSQL(
                    "INSERT INTO entries (id, lemma, key, pos, ipa, senses) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(id, entry.word, key, entry.pos, entry.ipa, deflate(entry.senses.toString()))
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO forms (key, entry_id, label) VALUES (?, ?, NULL)",
                    arrayOf(key, id)
                )
                for ((form, label) in entry.forms) {
                    if (form != key) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO forms (key, entry_id, label) VALUES (?, ?, ?)",
                            arrayOf(form, id, label)
                        )
                    }
                }

                if (written % BATCH == 0) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    onProgress(Progress(counted(), totalBytes, written))
                    db.beginTransaction()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress(counted(), totalBytes, written))
        return written
    }

    private fun finish(db: SQLiteDatabase, glossLanguage: String, wordLanguage: String, entries: Int) {
        // Resolve the form-of links now that every entry exists: a link names a lemma, and
        // only here is it known which entry that name is.
        db.execSQL("CREATE INDEX links_target ON links (target)")
        db.execSQL(
            """
            INSERT OR IGNORE INTO forms (key, entry_id, label)
            SELECT l.key, e.id, l.label FROM links l JOIN entries e ON e.key = l.target
            """.trimIndent()
        )
        db.execSQL("DROP TABLE links")
        db.execSQL("CREATE INDEX entries_key ON entries (key)")
        // The links table is most of what was written and none of what is kept: dropping it
        // only frees pages inside the file, so without this the pack stays twice its size.
        db.execSQL("VACUUM")
        for ((key, value) in listOf(
            "gloss_lang" to glossLanguage,
            "word_lang" to wordLanguage,
            "entries" to entries.toString(),
            "built" to System.currentTimeMillis().toString(),
            "source" to "wiktextract via kaikki.org",
            "format" to "3"
        )) {
            db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value))
        }
    }

    private class Parsed(
        val word: String,
        val pos: String?,
        val ipa: String?,
        val senses: JSONArray,
        val forms: List<Pair<String, String?>>,
        val links: List<Pair<String, String?>>
    )

    /** Reads one dump entry, skipping every field a pack has no use for. */
    private fun readEntry(reader: JsonReader): Parsed? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var word: String? = null
        var pos: String? = null
        var ipa: String? = null
        var senses = JSONArray()
        var forms = emptyList<Pair<String, String?>>()
        var links = emptyList<Pair<String, String?>>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "word" -> word = reader.nextString()
                "pos" -> pos = reader.nextStringOrNull()
                "sounds" -> ipa = readIpa(reader)
                "senses" -> {
                    val read = readSenses(reader)
                    senses = read.first
                    links = read.second
                }
                "forms" -> forms = readForms(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val name = word?.trim().orEmpty()
        if (name.isEmpty()) return null
        return Parsed(name, pos, ipa, senses, forms, links)
    }

    private fun readSenses(reader: JsonReader): Pair<JSONArray, List<Pair<String, String?>>> {
        val senses = JSONArray()
        val links = mutableListOf<Pair<String, String?>>()
        reader.beginArray()
        while (reader.hasNext()) {
            var gloss = ""
            var example: String? = null
            var tags = emptyList<String>()
            val targets = mutableListOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "glosses", "raw_glosses" ->
                        if (gloss.isEmpty()) gloss = readStrings(reader).joinToString("; ")
                        else reader.skipValue()
                    "tags" -> tags = readStrings(reader)
                    "examples" ->
                        if (senses.length() < SENSES_WITH_EXAMPLES) example = readFirstExample(reader)
                        else reader.skipValue()
                    "form_of", "alt_of" -> targets += readWordList(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            for (target in targets) links += normalise(target) to label(tags)
            if (targets.isNotEmpty()) continue
            if (gloss.isEmpty() || SKIP_TAGS.any { it in tags }) continue
            if (senses.length() >= MAX_SENSES) continue

            val sense = JSONObject().put("g", gloss.take(MAX_GLOSS))
            if (example != null) sense.put("x", JSONArray().put(example.take(MAX_EXAMPLE)))
            val keep = tags.filter { it !in LABEL_NOISE }.take(6)
            if (keep.isNotEmpty()) sense.put("t", JSONArray(keep))
            senses.put(sense)
        }
        reader.endArray()
        return senses to links
    }

    private fun readForms(reader: JsonReader): List<Pair<String, String?>> {
        val forms = LinkedHashMap<String, String?>()
        reader.beginArray()
        while (reader.hasNext()) {
            var text: String? = null
            var tags = emptyList<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "form" -> text = reader.nextStringOrNull()
                    "tags" -> tags = readStrings(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val form = text?.trim().orEmpty()
            // Romanisations are a different script, not another way the word appears in the
            // text someone is reading.
            if (form.isEmpty() || form == "-" || form.length > 80) continue
            if ("romanization" in tags || "transliteration" in tags) continue
            val key = normalise(form)
            val name = label(tags)
            if (key !in forms || (name != null && forms[key] == null)) forms[key] = name
        }
        reader.endArray()
        return forms.toList()
    }

    private fun readIpa(reader: JsonReader): String? {
        var ipa: String? = null
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "ipa" && ipa == null) ipa = reader.nextStringOrNull()
                else reader.skipValue()
            }
            reader.endObject()
        }
        reader.endArray()
        return ipa
    }

    private fun readFirstExample(reader: JsonReader): String? {
        var text: String? = null
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "text" && text == null) text = reader.nextStringOrNull()
                else reader.skipValue()
            }
            reader.endObject()
        }
        reader.endArray()
        return text?.trim()?.ifEmpty { null }
    }

    private fun readWordList(reader: JsonReader): List<String> {
        val words = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "word") reader.nextStringOrNull()?.let { words += it }
                else reader.skipValue()
            }
            reader.endObject()
        }
        reader.endArray()
        return words
    }

    private fun readStrings(reader: JsonReader): List<String> {
        val values = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.nextStringOrNull()?.let { values += it }
        }
        reader.endArray()
        return values
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun label(tags: List<String>): String? {
        val words = tags.filter { it !in LABEL_NOISE }.map { it.replace('-', ' ') }
        return if (words.isEmpty()) null else words.take(4).joinToString(" ")
    }

    /**
     * The shape a word is looked up by. Case is folded because a word at the start of a
     * sentence is the same word; nothing else is, since stripping accents would merge
     * distinct words in most languages this has to serve.
     */
    fun normalise(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase().trim()

    private fun deflate(json: String): ByteArray {
        val bytes = json.toByteArray()
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(bytes)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream(bytes.size / 2 + 32)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }
}
