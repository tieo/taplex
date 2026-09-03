package de.tieo.wordtap

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
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

    private fun state(
        lookup: Boolean = true,
        overlay: Boolean = true,
        installed: List<InstalledPack> = listOf(spanish),
        build: PackService.State = PackService.State.Idle
    ) = UiState(lookup, overlay, "en", installed, build)

    @Test
    fun `main screen nothing set up`() {
        paparazzi.snapshot("main-nothing-set-up-phone") {
            WordTapScreen(state(lookup = false, overlay = false, installed = emptyList()))
        }
    }

    @Test
    fun `main screen no dictionaries`() {
        paparazzi.snapshot("main-no-dictionaries-phone") {
            WordTapScreen(state(installed = emptyList()))
        }
    }

    @Test
    fun `main screen ready`() {
        paparazzi.snapshot("main-ready-phone") {
            WordTapScreen(state(installed = listOf(spanish, english)))
        }
    }

    @Test
    fun `main screen building`() {
        paparazzi.snapshot("main-building-phone") {
            WordTapScreen(
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
            WordTapScreen(
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
                        entries = listOf(
                            Entry(
                                lemma = "cocina",
                                pos = "noun",
                                ipa = "/koˈt͡ʃina/",
                                senses = listOf(
                                    Sense(
                                        "kitchen",
                                        listOf("La cocina es la habitación más grande."),
                                        listOf("feminine")
                                    ),
                                    Sense("cuisine, cooking", emptyList(), emptyList()),
                                    Sense("stove, cooker", emptyList(), listOf("Spain"))
                                ),
                                label = null
                            ),
                            Entry(
                                lemma = "cocinar",
                                pos = "verb",
                                ipa = null,
                                senses = listOf(Sense("to cook", emptyList(), emptyList())),
                                label = "indicative present singular third person"
                            )
                        ),
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
                        tapped = "Küchenwerkzeug",
                        entries = emptyList(),
                        glossLanguage = "en",
                        translation = "kitchen tool"
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
                        tapped = "when",
                        entries = emptyList(),
                        glossLanguage = "en",
                        translation = null,
                        note = "No English dictionary installed, so nothing explains this word in English."
                    )
                }
            }
        }
    }

    @Test
    fun `word layer over a screen`() {
        paparazzi.snapshot("layer-words-phone") {
            AndroidView { context ->
                FrameLayout(context).apply {
                    setBackgroundColor(Color.WHITE)
                    addView(
                        WordLayerView(
                            context = context,
                            frame = null,
                            sourceWidth = 1080,
                            onWordTapped = {},
                            onMissTapped = {},
                            onLongPressed = {}
                        ).apply {
                            setWords(
                                listOf(
                                    Word("La", Rect(60, 300, 130, 360), ""),
                                    Word("cocina", Rect(150, 300, 420, 360), ""),
                                    Word("es", Rect(440, 300, 520, 360), ""),
                                    Word("la", Rect(540, 300, 610, 360), ""),
                                    Word("habitación", Rect(60, 390, 470, 450), ""),
                                    Word("más", Rect(490, 390, 610, 450), ""),
                                    Word("grande", Rect(630, 390, 880, 450), "")
                                )
                            )
                        },
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        }
    }

    /** Renders a plain view inside a composition, since two of these screens are views. */
    @Composable
    private fun AndroidView(factory: (Context) -> View) {
        ComposeAndroidView(factory = factory, modifier = Modifier.fillMaxWidth())
    }
}
