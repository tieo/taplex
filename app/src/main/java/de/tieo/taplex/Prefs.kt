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
     * Which side of the screen the mark lives on.
     *
     * One side, always the same one: a handle that parks itself on whichever side the hand
     * happened to end on is a handle that has to be found again every time.
     */
    var markOnRight: Boolean
        get() = sp.getBoolean(KEY_MARK_RIGHT, true)
        set(value) = sp.edit().putBoolean(KEY_MARK_RIGHT, value).apply()

    /**
     * Whether the circle comes up over every app, which is what it does unless told
     * otherwise: reading happens wherever text is, and a reader who wants it in one app
     * says so rather than being made to name the app before anything works at all.
     */
    var hoverEverywhere: Boolean
        get() = sp.getBoolean(KEY_HOVER_ALL, true)
        set(value) = sp.edit().putBoolean(KEY_HOVER_ALL, value).apply()

    /**
     * The apps the circle belongs to, when it does not belong to all of them. A conversation happens in one app at a time and a
     * bubble sitting over every app is in the way of all of them, but one app is not one
     * package: the Gemini icon on a Pixel opens the Google app's assistant surface, and
     * the Gemini app's own entry hands off to it, so both packages are the same
     * conversation to the person having it.
     */
    var hoverPackages: Set<String>
        get() = sp.getStringSet(KEY_HOVER_PACKAGES, null) ?: GEMINI
        set(value) = sp.edit().putStringSet(KEY_HOVER_PACKAGES, value).apply()

    companion object {
        const val AUTO = "auto"

        /** Where Gemini is: its own app, and the Google app surface its icon opens. */
        val GEMINI = setOf(
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox"
        )

        private const val KEY_SOURCE = "source"
        private const val KEY_TARGET = "target"
        private const val KEY_HOVER = "hover"
        private const val KEY_HOVER_ALL = "hoverEverywhere"
        private const val KEY_MARK_RIGHT = "markOnRight"
        private const val KEY_HOVER_PACKAGES = "hoverPackages"
    }
}
