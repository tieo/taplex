package de.tieo.wordtap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The readings and neighbour parts of speech here are what the German pack built from
 * Wiktionary actually holds for these words, so these cases are the real ones: "Speisen" is
 * the plural of the noun "Speise" and also the verb "speisen", and "der" is an article, a
 * pronoun and a name at once.
 */
class ReadingTest {

    private val speiseNoun = Entry(
        lemma = "Speise",
        pos = "noun",
        ipa = null,
        senses = listOf(sense("meal, fare"), sense("dish")),
        label = "plural"
    )
    private val speisenVerb = Entry(
        lemma = "speisen",
        pos = "verb",
        ipa = null,
        senses = List(5) { sense("to dine $it") },
        label = null
    )
    private val speisNoun = Entry(
        lemma = "Speis",
        pos = "noun",
        ipa = null,
        senses = listOf(sense("larder")),
        label = "plural"
    )

    private val article = setOf("article", "name", "pron")
    private val pronoun = setOf("pron")

    @Test
    fun `a word after an article is the noun`() {
        val ranked = Reading.rank(
            tapped = "Speisen",
            candidates = listOf(speisenVerb, speiseNoun, speisNoun),
            beforePos = article,
            afterPos = emptySet()
        )
        assertEquals("Speise", ranked.first().lemma)
    }

    @Test
    fun `a word after a pronoun is the verb`() {
        val ranked = Reading.rank(
            tapped = "speisen",
            candidates = listOf(speiseNoun, speisenVerb),
            beforePos = pronoun,
            afterPos = emptySet()
        )
        assertEquals("speisen", ranked.first().lemma)
    }

    @Test
    fun `without context the entry the word is the lemma of comes first`() {
        val ranked = Reading.rank(
            tapped = "speisen",
            candidates = listOf(speiseNoun, speisenVerb),
            beforePos = emptySet(),
            afterPos = emptySet()
        )
        assertEquals("speisen", ranked.first().lemma)
    }

    @Test
    fun `neighbours come from the line the word was read from`() {
        val (before, after) = Reading.neighbours(
            "hygienische Zubereitung der Speisen eine wichtige Rolle",
            "Speisen"
        )
        assertEquals("der", before)
        assertEquals("eine", after)
    }

    private fun sense(gloss: String) = Sense(gloss, emptyList(), emptyList())
}
