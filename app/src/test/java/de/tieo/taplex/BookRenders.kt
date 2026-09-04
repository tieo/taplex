package de.tieo.taplex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView as ComposeAndroidView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test

/**
 * The pictures in `docs/model`, taken from the real screens without a phone.
 *
 * Every state the book declares is rendered here, so a state nobody can produce by hand is
 * still looked at, and `viewbook --gaps docs/model` can hold the app to having one picture
 * per state. `tools/make-renders.sh` runs these and puts the results where the book reads
 * them.
 */
class BookRenders {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false),
        renderingMode = SessionParams.RenderingMode.SHRINK,
        showSystemUi = false
    )

    private val spanish = InstalledPack("es", "en", 88_000_000)
    private val english = InstalledPack("en", "en", 310_000_000)

    /** One line as it was spoken, where a transcript would put it. */
    private val SPOKEN = listOf(
        Word("La", Rect(60, 300, 130, 360), "La cocina es la habitación más grande."),
        Word("cocina", Rect(150, 300, 420, 360), "La cocina es la habitación más grande."),
        Word("es", Rect(440, 300, 520, 360), "La cocina es la habitación más grande."),
        Word("la", Rect(540, 300, 610, 360), "La cocina es la habitación más grande."),
        Word("habitación", Rect(60, 390, 470, 450), "La cocina es la habitación más grande."),
        Word("más", Rect(490, 390, 610, 450), "La cocina es la habitación más grande."),
        Word("grande", Rect(630, 390, 880, 450), "La cocina es la habitación más grande.")
    )

    /** The word the circle is over in the hover renders. */
    private val HOVERED = SPOKEN[1].bounds

    private val kitchen = Entry(
        lemma = "cocina",
        pos = "noun",
        ipa = "/koˈt͡ʃina/",
        senses = listOf(
            Sense("kitchen", listOf("La cocina es la habitación más grande."), listOf("feminine")),
            Sense("cuisine, cooking", emptyList(), emptyList()),
            Sense("stove, cooker", emptyList(), listOf("Spain"))
        ),
        label = null
    )
    private val toCook = Entry(
        lemma = "cocinar",
        pos = "verb",
        ipa = null,
        senses = listOf(Sense("to cook", emptyList(), emptyList())),
        label = "indicative present singular third person"
    )

    private fun state(
        lookup: Boolean = true,
        overlay: Boolean = true,
        installed: List<InstalledPack> = listOf(spanish),
        build: PackService.State = PackService.State.Idle
    ) = UiState(lookup, overlay, "en", installed, build)

    @Test
    fun `main screen nothing set up`() {
        paparazzi.snapshot("main-nothing-set-up-phone") {
            TaplexScreen(state(lookup = false, overlay = false, installed = emptyList()))
        }
    }

    @Test
    fun `main screen no dictionaries`() {
        paparazzi.snapshot("main-no-dictionaries-phone") {
            TaplexScreen(state(installed = emptyList()))
        }
    }

    @Test
    fun `main screen ready`() {
        paparazzi.snapshot("main-ready-phone") {
            TaplexScreen(state(installed = listOf(spanish, english)))
        }
    }

    @Test
    fun `main screen building`() {
        paparazzi.snapshot("main-building-phone") {
            TaplexScreen(
                state(
                    installed = emptyList(),
                    build = PackService.State.Working("es", 34_000_000, 91_000_000, 42_000)
                )
            )
        }
    }

    @Test
    fun `main screen build failed`() {
        paparazzi.snapshot("main-failed-phone") {
            TaplexScreen(
                state(
                    installed = emptyList(),
                    build = PackService.State.Failed("ga", "Wiktionary has no Irish dictionary to build from.")
                )
            )
        }
    }

    @Test
    fun `entry card with an entry`() {
        paparazzi.snapshot("entry-found-phone") {
            AndroidView { context ->
                EntryView(context).apply {
                    showEntries(
                        tapped = "cocina",
                        entries = listOf(kitchen, toCook),
                        glossLanguage = "en",
                        translation = null
                    )
                }
            }
        }
    }

    @Test
    fun `entry card without an entry`() {
        paparazzi.snapshot("entry-none-phone") {
            AndroidView { context ->
                EntryView(context).apply {
                    showEntries(
                        tapped = "tuper",
                        entries = emptyList(),
                        glossLanguage = "en",
                        translation = "food container"
                    )
                }
            }
        }
    }

    @Test
    fun `entry card without a dictionary`() {
        paparazzi.snapshot("entry-no-pack-phone") {
            AndroidView { context ->
                EntryView(context).apply {
                    showEntries(
                        tapped = "cocina",
                        entries = emptyList(),
                        glossLanguage = "en",
                        translation = null,
                        note = "No Spanish dictionary installed, so nothing explains this word in English."
                    )
                }
            }
        }
    }

    @Test
    fun `language picker unfiltered`() {
        val languages = PackSource.languages("en")
        paparazzi.snapshot("pick-all-phone") {
            MaterialTheme {
                Surface {
                    LanguagePicker(
                        shown = languages,
                        query = "",
                        onQueryChange = {},
                        onPick = {}
                    )
                }
            }
        }
    }

    @Test
    fun `language picker searched`() {
        val languages = PackSource.languages("en")
        paparazzi.snapshot("pick-search-phone") {
            MaterialTheme {
                Surface {
                    LanguagePicker(
                        shown = PackSource.matching(languages, "spa"),
                        query = "spa",
                        onQueryChange = {},
                        onPick = {}
                    )
                }
            }
        }
    }

    @Test
    fun `word layer over a screen`() {
        val sentence = SPOKEN
        paparazzi.snapshot("layer-words-phone") {
            AndroidView { context ->
                FrameLayout(context).apply {
                    setBackgroundColor(Color.WHITE)
                    addView(
                        WordLayerView(
                            context = context,
                            frame = capturedFrame(sentence),
                            sourceWidth = SOURCE_WIDTH,
                            onWordTapped = {},
                            onMissTapped = {},
                            onLongPressed = {}
                        ).apply { setWords(sentence) },
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `hover circle parked`() {
        paparazzi.snapshot("hover-parked-phone") {
            AndroidView { context -> conversation(context, circleAt = null, marked = null) }
        }
    }

    @Test
    fun `hover circle on a word`() {
        paparazzi.snapshot("hover-word-phone") {
            AndroidView { context ->
                conversation(
                    context,
                    circleAt = HOVERED,
                    marked = HOVERED,
                    card = { view ->
                        view.showEntries(
                            tapped = "cocina",
                            entries = listOf(kitchen, toCook),
                            glossLanguage = "en",
                            translation = null
                        )
                    }
                )
            }
        }
    }

    @Test
    fun `hover circle on a word with no entry`() {
        paparazzi.snapshot("hover-none-phone") {
            AndroidView { context ->
                conversation(
                    context,
                    circleAt = HOVERED,
                    marked = HOVERED,
                    card = { view ->
                        view.showEntries(
                            tapped = "tuper",
                            entries = emptyList(),
                            glossLanguage = "en",
                            translation = "food container"
                        )
                    }
                )
            }
        }
    }

    @Test
    fun `say it asked`() {
        paparazzi.snapshot("say-asked-phone") {
            AndroidView { context ->
                SayInputView(context).apply { askFor("Spanish") }
            }
        }
    }

    @Test
    fun `say it answered`() {
        paparazzi.snapshot("say-answered-phone") {
            AndroidView { context ->
                SayInputView(context).apply {
                    askFor("Spanish")
                    field.setText("hint")
                    show(
                        Explanation(
                            term = "hint",
                            entries = listOf(
                                Entry(
                                    lemma = "pista",
                                    pos = "noun",
                                    ipa = "/ˈpista/",
                                    senses = listOf(
                                        Sense("clue, hint", listOf("No tengo ninguna pista."), listOf("feminine")),
                                        Sense("track, trail", emptyList(), emptyList()),
                                        Sense("runway", emptyList(), listOf("aviation"))
                                    ),
                                    label = null
                                )
                            ),
                            translation = "pista",
                            note = null,
                            glossLanguage = "en"
                        )
                    )
                }
            }
        }
    }

    /**
     * The conversation the circle is dragged over: the words of a spoken line where the
     * layer would find them, with the real circle, mark and card composed the way
     * [HoverController] puts them on screen.
     */
    private fun conversation(
        context: Context,
        circleAt: Rect?,
        marked: Rect?,
        card: ((EntryView) -> Unit)? = null
    ): View {
        val density = context.resources.displayMetrics.density
        val frame = FrameLayout(context)
        frame.setBackgroundColor(Color.WHITE)
        frame.addView(
            AndroidViewOf(context, capturedFrame(SPOKEN)),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, SOURCE_HEIGHT)
        )
        frame.addView(
            HoverHighlightView(context).apply { mark(marked) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        if (card != null) {
            val view = EntryView(context)
            card(view)
            frame.addView(
                view,
                FrameLayout.LayoutParams((SOURCE_WIDTH * 0.82f).toInt(), WRAP).apply {
                    leftMargin = marked?.left ?: 0
                    topMargin = (marked?.bottom ?: 0) + (8 * density).toInt()
                }
            )
        }
        val size = (40 * density).toInt()
        frame.addView(
            HoverBubbleView(context).apply { active = circleAt != null },
            FrameLayout.LayoutParams(size, size).apply {
                // Parked at the side, or riding above the finger on the word it is reading.
                leftMargin = circleAt?.let { it.left + it.width() / 2 - size / 2 }
                    ?: (SOURCE_WIDTH - size - (16 * density).toInt())
                topMargin = circleAt?.let { it.top - size / 2 } ?: (SOURCE_HEIGHT / 2)
            }
        )
        return frame
    }

    /** A view that just draws the given bitmap, standing in for the screen underneath. */
    private fun AndroidViewOf(context: Context, bitmap: Bitmap): View =
        object : View(context) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        }

    /**
     * Stands in for the screenshot the layer freezes: the words are painted where their
     * boxes are, so the render shows boxes sitting on the text they were read from rather
     * than on empty space.
     */
    private fun capturedFrame(words: List<Word>): Bitmap {
        val frame = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 27)
            textSize = 46f
        }
        for (word in words) {
            canvas.drawText(word.text, word.bounds.left.toFloat(), word.bounds.bottom - 12f, ink)
        }
        return frame
    }

    private companion object {
        /** The pixel size the word boxes below are measured in: a phone screen, portrait. */
        const val SOURCE_WIDTH = 1080
        const val SOURCE_HEIGHT = 2400
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }

    /** Renders a plain view inside a composition, since two of these screens are views. */
    @Composable
    private fun AndroidView(factory: (Context) -> View) {
        ComposeAndroidView(factory = factory, modifier = Modifier.fillMaxWidth())
    }
}
