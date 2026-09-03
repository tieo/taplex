package de.tieo.taplex

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The readings and the parts of speech here are what the Spanish pack built from Wiktionary
 * actually holds: "cocina" is the noun for a kitchen and the third person present of the
 * verb "cocinar", "la" is an article, a noun and a pronoun at once, and "se" is a pronoun.
 * Which of the two a reader means is decided by the sentence, so these are the cases that
 * decide it.
 */
class ReadingTest {

    private val kitchen = Entry(
        lemma = "cocina",
        pos = "noun",
        ipa = "/koˈt͡ʃina/",
        senses = listOf(sense("kitchen"), sense("cuisine"), sense("stove")),
        label = null
    )
    private val toCook = Entry(
        lemma = "cocinar",
        pos = "verb",
        ipa = null,
        senses = listOf(sense("to cook")),
        label = "indicative present singular third person"
    )

    private val article = setOf("article", "noun", "pron")
    private val pronoun = setOf("pron")

    @Test
    fun `after an article the word is the noun`() {
        val ranked = Reading.rank(
            tapped = "cocina",
            candidates = listOf(toCook, kitchen),
            beforePos = article,
            afterPos = emptySet()
        )
        assertEquals("cocina", ranked.first().lemma)
    }

    @Test
    fun `after a pronoun the word is the verb`() {
        val ranked = Reading.rank(
            tapped = "cocina",
            candidates = listOf(kitchen, toCook),
            beforePos = pronoun,
            afterPos = emptySet()
        )
        assertEquals("cocinar", ranked.first().lemma)
    }

    @Test
    fun `with nothing to go on the entry the word is the lemma of comes first`() {
        val ranked = Reading.rank(
            tapped = "cocina",
            candidates = listOf(toCook, kitchen),
            beforePos = emptySet(),
            afterPos = emptySet()
        )
        assertEquals("cocina", ranked.first().lemma)
    }

    @Test
    fun `neighbours come from the line the word was read from`() {
        val (before, after) = Reading.neighbours(
            "• cocina (habitación), lugar donde se cocina;",
            "cocina"
        )
        // The first occurrence is the one at the start of the line, whose neighbour on the
        // left is nothing at all.
        assertEquals(null, before)
        assertEquals("habitación", after)
    }

    @Test
    fun `punctuation around a word does not hide its neighbours`() {
        val (before, after) = Reading.neighbours(
            "el arte de cocinar, la gastronomía.",
            "gastronomía"
        )
        assertEquals("la", before)
        assertEquals(null, after)
    }

    private fun sense(gloss: String) = Sense(gloss, emptyList(), emptyList())
}
