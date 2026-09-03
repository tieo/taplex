package de.tieo.taplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * A pack built from a handful of dump lines, read back through the same [Dictionary] the app
 * looks words up with.
 *
 * The fixture is shaped like the Wiktextract lines a real dump carries: an entry with
 * several senses and an inflection table, an entry that is nothing but a pointer at another
 * one, and a sense marked as carrying no meaning of its own.
 */
@RunWith(RobolectricTestRunner::class)
class PackBuilderTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    private val dump = """
        {"word":"cocina","pos":"noun","sounds":[{"ipa":"/koˈtʃina/"}],
         "senses":[{"glosses":["kitchen"],"tags":["feminine"],
                    "examples":[{"text":"La cocina es la habitación más grande."}]},
                   {"glosses":["cuisine, cooking"]}],
         "forms":[{"form":"cocinas","tags":["plural"]},
                  {"form":"kocina","tags":["romanization"]}]}
        {"word":"cocinas","pos":"noun",
         "senses":[{"glosses":["plural of cocina"],"form_of":[{"word":"cocina"}],"tags":["plural"]}]}
        {"word":"cocinar","pos":"verb","senses":[{"glosses":["to cook"]}]}
        {"word":"borrador","pos":"noun","senses":[{"glosses":["draft"],"tags":["no-gloss"]}]}
    """.trimIndent().lines().joinToString("\n") { it.trim() }

    private fun build(): File {
        val target = File(context.cacheDir, "en-es.db")
        target.delete()
        val entries = PackBuilder.build(
            input = dump.byteInputStream(),
            target = target,
            glossLanguage = "en",
            wordLanguage = "es",
            totalBytes = dump.length.toLong(),
            counted = { dump.length.toLong() },
            cancelled = { false },
            onProgress = {}
        )
        assertEquals("entries written", 2, entries)
        return target
    }

    private fun dictionary(): Dictionary {
        val pack = build()
        val directory = Dictionary.directory(context)
        pack.copyTo(File(directory, pack.name), overwrite = true)
        return checkNotNull(Dictionary.open(context, "en", "es")) { "the pack did not open" }
    }

    @Test
    fun `an entry keeps its senses, its marks and its example`() {
        val entry = dictionary().look("cocina").single { it.pos == "noun" }
        assertEquals("cocina", entry.lemma)
        assertEquals("/koˈtʃina/", entry.ipa)
        assertEquals(listOf("kitchen", "cuisine, cooking"), entry.senses.map { it.gloss })
        assertEquals(listOf("feminine"), entry.senses.first().tags)
        assertEquals(
            listOf("La cocina es la habitación más grande."),
            entry.senses.first().examples
        )
    }

    @Test
    fun `an inflected spelling reaches the entry it belongs to and is named`() {
        val entry = dictionary().look("cocinas").single()
        assertEquals("cocina", entry.lemma)
        assertEquals("plural", entry.label)
    }

    @Test
    fun `an entry that is only a pointer at another one is not an entry of its own`() {
        // Written as a row of its own it would answer a tap with a lemma and nothing under
        // it, which is what the pack's size and its emptiness both hang on.
        val entries = dictionary().look("cocinas")
        assertEquals(1, entries.size)
        assertTrue(entries.single().senses.isNotEmpty())
    }

    @Test
    fun `a sense that carries no meaning leaves no entry`() {
        assertEquals(emptyList<Entry>(), dictionary().look("borrador"))
    }

    @Test
    fun `a romanisation is not a spelling anyone reads`() {
        assertEquals(emptyList<Entry>(), dictionary().look("kocina"))
    }

    @Test
    fun `the pack says what it is`() {
        val pack = dictionary()
        assertEquals("en", pack.glossLanguage)
        assertEquals("es", pack.wordLanguage)
        assertNull(pack.look("cocina").first().label)
    }
}
