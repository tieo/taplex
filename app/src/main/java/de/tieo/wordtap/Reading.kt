package de.tieo.wordtap

/**
 * Picking which entry a word on the page actually is.
 *
 * A spelling usually leads to several entries: "Speisen" is the plural of the noun "Speise"
 * and also a form of the verb "speisen", and a dictionary that shows the verb first for
 * "Zubereitung der Speisen" is showing the wrong word. The sentence settles it, and the pack
 * itself is what the sentence is read with: the words around the tapped one are looked up
 * too, and what parts of speech they can be says what the tapped word is likely to be.
 *
 * Everything here is stated in parts of speech, never in words of any one language, so it
 * works wherever the pack does.
 */
object Reading {

    /** Words that stand in front of a noun phrase. */
    private val BEFORE_NOUN = setOf("det", "article", "prep", "postp", "num")

    /** Words a verb tends to follow. */
    private val BEFORE_VERB = setOf("pron", "adv", "conj", "particle")

    private val NOUNISH = setOf("noun", "name", "proper noun")
    private val MODIFIER = setOf("adj", "adv", "det", "num")

    /** The tokens of a line, in order, as the word regex sees them. */
    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")

    fun tokens(line: String): List<String> = TOKEN.findAll(line).map { it.value }.toList()

    /** The word before and after [word] in [line], as far as the line holds them. */
    fun neighbours(line: String, word: String): Pair<String?, String?> {
        val tokens = tokens(line)
        val index = tokens.indexOfFirst { it.equals(word, ignoreCase = true) }
        if (index < 0) return null to null
        return tokens.getOrNull(index - 1) to tokens.getOrNull(index + 1)
    }

    /**
     * Orders [candidates] by how well each fits the sentence, best first.
     *
     * [beforePos] and [afterPos] are the parts of speech the neighbouring words can have,
     * which is a set rather than a value: a word that is both an article and a pronoun
     * pulls towards a noun and towards a verb at once, and the strengths decide.
     */
    fun rank(
        tapped: String,
        candidates: List<Entry>,
        beforePos: Set<String>,
        afterPos: Set<String>
    ): List<Entry> =
        candidates.sortedByDescending { score(tapped, it, beforePos, afterPos) }

    private fun score(tapped: String, entry: Entry, beforePos: Set<String>, afterPos: Set<String>): Int {
        var score = 0
        val pos = entry.pos?.lowercase().orEmpty()

        // What stands before the word says most about what it is, and the signals are
        // ranked rather than added up: a word that can be an article and a pronoun at once
        // is an article far more often when something follows it that can be a noun, so the
        // stronger reading decides instead of the two cancelling out.
        when {
            beforePos.any { it in BEFORE_NOUN } -> score += when {
                pos in NOUNISH -> 6
                pos in MODIFIER -> 3
                else -> -4
            }
            beforePos.any { it in BEFORE_VERB } -> score += when {
                pos == "verb" -> 4
                pos in NOUNISH -> -1
                else -> 0
            }
        }
        // A word followed by a noun is more likely to be describing it than to be a noun
        // itself.
        if (afterPos.any { it in NOUNISH } && pos in MODIFIER) score += 2

        // A spelling that is the entry's own lemma needs no inflection to explain it, and
        // is the likelier reading where the sentence says nothing either way.
        if (entry.label == null) score += 2
        if (entry.lemma == tapped) score += 2

        // Between two readings that fit equally, the entry with more to say is the more
        // useful answer.
        score += entry.senses.size.coerceAtMost(3)
        return score
    }
}
