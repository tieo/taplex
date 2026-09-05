package de.tieo.taplex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What came back about one word: the entries the pack holds for it, or the guess and the
 * reason there are none.
 *
 * A missing pack and a missing word are different answers, so [note] carries the second
 * one: a word this dictionary does not have is not the same as a language with no
 * dictionary at all, and only the second can be fixed by installing something.
 */
data class Explanation(
    val term: String,
    val entries: List<Entry>,
    val translation: String?,
    val note: String?,
    val glossLanguage: String
)

/**
 * Answering a word, which is the one thing both ways into Taplex share.
 *
 * The modal layer asks it about a word someone tapped; the hover circle asks it about
 * whatever word it is passing over. It holds the open pack and the translator between
 * questions, since both are expensive to open and a hover asks several times a second.
 */
class Lookup(private val context: Context) {

    private val prefs = Prefs(context)
    private val translator = WordTranslator()
    private var dictionary: Dictionary? = null

    /** The language on screen, once something has been read. Null until then. */
    var sourceLanguage: String? = null
        private set

    /** The language explanations are written in: the one the phone is set to. */
    val glossLanguage: String get() = prefs.targetLanguage

    /** Identifies the language of what is on screen and remembers it for later lookups. */
    suspend fun identify(prose: String): String? {
        val installed = Dictionary.installed(context)
            .filter { it.first == glossLanguage }
            .map { it.second }
            .toSet()
        sourceLanguage = translator.identify(prose, prefs.sourceLanguage, installed)
        return sourceLanguage
    }

    /**
     * The language being learned: what is on screen when that is a foreign language, and
     * otherwise the words language of the installed pack.
     *
     * The screen alone is not enough to go on. A conversation in the language being learned
     * is full of the explanation language too, from the app's own chrome to a reply written
     * in English, and reading one of those screens would otherwise answer "the word for
     * this in English" with the English word.
     */
    fun learningLanguage(): String? =
        sourceLanguage?.takeIf { it != glossLanguage }
            ?: Dictionary.installed(context)
                .filter { it.first == glossLanguage }
                .map { it.second }
                .singleOrNull()

    /**
     * [term] as the pack explains it, with a machine translation only where the pack has no
     * entry. A dictionary says what a word means, in how many ways, and how it is used; a
     * translation of a single word pulled out of its sentence is one guess at one of those.
     *
     * [line] is the text the word was read from, which decides which reading comes first.
     */
    suspend fun explain(term: String, line: String = "", source: String? = null): Explanation {
        val target = glossLanguage
        val from = source ?: sourceLanguage ?: learningLanguage()
        if (from == null) {
            return Explanation(term, emptyList(), null, null, target)
        }
        val pack = withContext(Dispatchers.IO) { pack(from, target) }
        val entries = withContext(Dispatchers.IO) { pack?.look(term, line).orEmpty() }
        if (entries.isNotEmpty()) {
            return Explanation(term, entries, null, null, target)
        }
        val note = if (pack == null) {
            context.getString(R.string.no_pack, languageName(from), languageName(target))
        } else {
            null
        }
        // Translating a word into the language it is already in hands back the word, which
        // says nothing and reads as an answer.
        val guess = if (from == target) Guess.None else translate(term, from, target)
        return Explanation(
            term = term,
            entries = emptyList(),
            translation = (guess as? Guess.Word)?.text,
            note = (guess as? Guess.Waiting)?.message ?: note,
            glossLanguage = target
        )
    }

    /**
     * The other direction: something said in the language of the explanations, answered
     * with the word for it in the language being learned, explained as an entry of its own.
     *
     * The translation is a guess at the word; the entry under it is what says whether that
     * guess is the word that was meant, since it carries the senses, the marks and an
     * example the guess alone cannot.
     */
    suspend fun say(phrase: String): Explanation {
        val target = glossLanguage
        val learning = learningLanguage()
        if (learning == null || learning == target) {
            // Nothing to say it in: no pack is installed, so there is no language to answer
            // with and nothing that could explain the answer if there were.
            return Explanation(
                phrase,
                emptyList(),
                null,
                context.getString(R.string.say_no_pack),
                target
            )
        }
        val word = when (val guess = translate(phrase, target, learning)) {
            is Guess.Word -> guess.text
            is Guess.Waiting ->
                return Explanation(phrase, emptyList(), null, guess.message, target)
            Guess.None ->
                return Explanation(
                    phrase,
                    emptyList(),
                    null,
                    context.getString(R.string.no_translation, languageName(learning)),
                    target
                )
        }
        // The entry is looked up for the word that came back, not for what was typed.
        return explain(word, source = learning).let {
            if (it.entries.isEmpty()) it.copy(translation = word) else it
        }
    }

    private fun pack(source: String, target: String): Dictionary? {
        // The open pack is kept between lookups, but only for the pair it was opened for: a
        // screen in another language must not be answered out of it.
        val open = dictionary
        if (open != null && open.glossLanguage == target && open.wordLanguage == source) {
            return open
        }
        open?.close()
        dictionary = null
        return Dictionary.open(context, target, source)?.also { dictionary = it }
    }

    /**
     * A machine translation is either a word or a reason there is none. They are kept apart
     * because a model that is still downloading has to be said as the wait it is, and never
     * shown, or looked up, as if it were the word someone asked for.
     */
    private sealed interface Guess {
        data class Word(val text: String) : Guess
        data class Waiting(val message: String) : Guess
        data object None : Guess
    }

    private suspend fun translate(term: String, source: String, target: String): Guess =
        when (val result = translator.translate(term, source, target, allowDownload = true)) {
            is WordTranslator.Result.Ok ->
                // The model hands unknown words back unchanged; that is not a translation.
                result.text.takeIf { !it.equals(term, ignoreCase = true) }
                    ?.let { Guess.Word(it) } ?: Guess.None
            is WordTranslator.Result.NeedsDownload ->
                Guess.Waiting(
                    context.getString(R.string.downloading_model, result.source, result.target)
                )
            is WordTranslator.Result.Failed -> Guess.None
        }

    fun close() {
        translator.close()
        dictionary?.close()
        dictionary = null
    }

    companion object {
        fun languageName(tag: String): String =
            java.util.Locale(tag).displayLanguage.ifEmpty { tag }
    }
}
