package de.tieo.taplex

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * One word on screen, where it sits, and the line it was read from. The line is what tells
 * a lookup which reading of the word is on the page: the same spelling is a noun in one
 * sentence and a verb in the next.
 */
data class Word(val text: String, val bounds: Rect, val line: String = "")

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
                    words += Word(element.text, box, line.text)
                }
            }
        }
        return Recognised(words, result.text, result.textBlocks.map { it.text })
    }
}
