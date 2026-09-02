package de.tieo.wordtap

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** One recognised word and where it sits in the captured bitmap. */
data class Word(val text: String, val bounds: Rect)

/** Everything the recogniser found in one frame. */
data class Recognised(val words: List<Word>, val fullText: String, val blocks: List<String>) {

    /**
     * The longest blocks of prose on screen. Language identification on the whole frame is
     * unreliable because a screenshot is mostly chrome: clock, battery, URL bar, button
     * labels. The body text is what carries the language.
     */
    fun prose(): String =
        blocks.sortedByDescending { it.length }
            .take(3)
            .joinToString(" ")
            .replace(Regex("[^\\p{L} ]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
}

object Ocr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * ML Kit returns Text -> TextBlock -> Line -> Element, where an Element is a word or
     * word-like entity. Elements are what a tap is matched against.
     */
    suspend fun run(bitmap: Bitmap): Recognised {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val words = mutableListOf<Word>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    words += Word(element.text, box)
                }
            }
        }
        return Recognised(words, result.text, result.textBlocks.map { it.text })
    }
}
