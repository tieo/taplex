package de.tieo.taplex

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The picker offers every language the phone can name, so which of them a few typed letters
 * leave is the whole of how anyone reaches the one they came for.
 */
class PackSourceTest {

    private fun language(name: String) =
        PackSource.Available(code = name.take(2).lowercase(), name = name, url = "")

    private val languages = listOf(
        language("Spanish"),
        language("Japanese"),
        language("Old Spanish"),
        language("Panjabi")
    )

    @Test
    fun `nothing typed leaves the list alone`() {
        assertEquals(languages, PackSource.matching(languages, "  "))
    }

    @Test
    fun `a name that starts with the letters comes before one that merely contains them`() {
        assertEquals(
            listOf("Spanish", "Old Spanish"),
            PackSource.matching(languages, "spa").map { it.name }
        )
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertEquals(
            listOf("Japanese"),
            PackSource.matching(languages, " JAPAN ").map { it.name }
        )
    }

    @Test
    fun `a name nothing answers leaves nothing`() {
        assertEquals(emptyList<PackSource.Available>(), PackSource.matching(languages, "zzz"))
    }
}
