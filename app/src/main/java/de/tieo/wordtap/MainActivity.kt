package de.tieo.wordtap

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var dictionaries: TextView
    private lateinit var arm: Button

    private val languages: List<String> by lazy { TranslateLanguage.getAllLanguages().sorted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        dictionaries = findViewById(R.id.dictionaries)
        arm = findViewById(R.id.arm)

        setUpLanguagePickers()

        findViewById<Button>(R.id.wordLookup).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.overlayPermission).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        arm.setOnClickListener {
            if (CaptureService.running) {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    status.text = "Allow drawing over other apps first."
                    return@setOnClickListener
                }
                requestNotificationPermission()
                startActivity(ProjectionRequestActivity.intent(this))
            }
        }
    }

    private fun setUpLanguagePickers() {
        val sourceEntries = listOf(Prefs.AUTO) + languages
        val source = findViewById<Spinner>(R.id.source)
        source.adapter = adapterOf(sourceEntries.map { label(it) })
        source.setSelection(sourceEntries.indexOf(prefs.sourceLanguage).coerceAtLeast(0))
        source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.sourceLanguage = sourceEntries[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val target = findViewById<Spinner>(R.id.target)
        target.adapter = adapterOf(languages.map { label(it) })
        target.setSelection(languages.indexOf(prefs.targetLanguage).coerceAtLeast(0))
        target.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.targetLanguage = languages[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun adapterOf(entries: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)

    private fun label(tag: String): String =
        if (tag == Prefs.AUTO) "Detect automatically"
        else "${Locale(tag).displayLanguage} ($tag)"

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    /**
     * Read from the system rather than from the service's own flag: the setting survives
     * this process, the flag does not.
     */
    private fun wordLookupEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val name = ComponentName(this, WordTapAccessibilityService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == name
        }
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        status.text = buildString {
            append("Word lookup: ")
            append(if (wordLookupEnabled()) "on, nothing is recorded" else "off")
            append("\nOverlay permission: ")
            append(if (overlay) "granted" else "missing")
            append("\nScreen capture fallback: ")
            append(if (CaptureService.running) "armed, recording" else "not armed")
        }
        arm.text = if (CaptureService.running) "Disarm" else "Arm screen capture fallback"
        showDictionaries()
    }

    /**
     * Which dictionaries are installed, and where to put another one. A pack is a file
     * rather than a download inside the app, so this says the folder it is copied into.
     */
    private fun showDictionaries() {
        val installed = Dictionary.installed(this)
        dictionaries.text = buildString {
            if (installed.isEmpty()) {
                append("No dictionary installed. Words fall back to machine translation.")
            } else {
                append("Dictionaries: ")
                append(
                    installed.joinToString(", ") { (gloss, word) ->
                        "${name(word)} explained in ${name(gloss)}"
                    }
                )
            }
            append("\n")
            append(Dictionary.directory(this@MainActivity).path)
        }
    }

    private fun name(tag: String): String = Locale(tag).displayLanguage.ifEmpty { tag }
}
