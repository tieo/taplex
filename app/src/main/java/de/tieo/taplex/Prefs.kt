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

    /** Whether the hover circle comes up by itself in [hoverPackage]. */
    var hoverEnabled: Boolean
        get() = sp.getBoolean(KEY_HOVER, false)
        set(value) = sp.edit().putBoolean(KEY_HOVER, value).apply()

    /**
     * The app the circle belongs to. A conversation happens in one app at a time, and a
     * bubble sitting over every app is in the way of all of them.
     */
    var hoverPackage: String
        get() = sp.getString(KEY_HOVER_PACKAGE, GEMINI) ?: GEMINI
        set(value) = sp.edit().putString(KEY_HOVER_PACKAGE, value).apply()

    companion object {
        const val AUTO = "auto"

        /** The Gemini app, which is the conversation this was built for. */
        const val GEMINI = "com.google.android.apps.bard"

        private const val KEY_SOURCE = "source"
        private const val KEY_TARGET = "target"
        private const val KEY_HOVER = "hover"
        private const val KEY_HOVER_PACKAGE = "hoverPackage"
    }
}
