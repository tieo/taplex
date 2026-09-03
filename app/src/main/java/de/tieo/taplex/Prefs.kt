package de.tieo.taplex

import android.content.Context
import java.util.Locale

/** User settings. Source "auto" means the language is identified from the text on screen. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("taplex", Context.MODE_PRIVATE)

    var sourceLanguage: String
        get() = sp.getString(KEY_SOURCE, AUTO) ?: AUTO
        set(value) = sp.edit().putString(KEY_SOURCE, value).apply()

    var targetLanguage: String
        get() = sp.getString(KEY_TARGET, Locale.getDefault().language) ?: "en"
        set(value) = sp.edit().putString(KEY_TARGET, value).apply()

    companion object {
        const val AUTO = "auto"
        private const val KEY_SOURCE = "source"
        private const val KEY_TARGET = "target"
    }
}
