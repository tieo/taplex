package de.tieo.taplex

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The screen as the phone sees it: reads what is allowed and what is installed, and hands
 * that to [TaplexScreen], which knows nothing about where any of it came from.
 */
class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Main() } }
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun Main() {
    val context = LocalContext.current
    val build by PackService.states().collectAsStateWithLifecycle()
    val prefs = remember { Prefs(context) }

    // Permissions are granted on other screens and dictionaries appear as a build finishes,
    // so what the phone says is read again whenever this comes back into view.
    var reread by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var hovering by remember { mutableStateOf(prefs.hoverEnabled) }
    LaunchedEffect(build) { reread++ }

    val state = remember(reread, build, hovering) {
        UiState(
            lookupEnabled = lookupEnabled(context),
            canDrawOverlay = Settings.canDrawOverlays(context),
            glossLanguage = prefs.targetLanguage,
            installed = Dictionary.installed(context).map { (gloss, word) ->
                InstalledPack(word, gloss, Dictionary.file(context, gloss, word).length())
            },
            build = build,
            hoverEnabled = hovering
        )
    }

    TaplexScreen(
        state = state,
        actions = ScreenActions(
            onEnableLookup = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onAllowOverlay = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
            onAddDictionary = { picking = true },
            onDeleteDictionary = { pack ->
                Dictionary.file(context, pack.glossLanguage, pack.wordLanguage).delete()
                reread++
            },
            onCancelBuild = { PackService.cancel(context) },
            onHoverChanged = { wanted ->
                prefs.hoverEnabled = wanted
                hovering = wanted
            }
        )
    )

    if (picking) {
        val languages = remember(state.glossLanguage) { PackSource.languages(state.glossLanguage) }
        var query by remember { mutableStateOf("") }
        val shown = remember(languages, query) { PackSource.matching(languages, query) }
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.pick_language)) },
            text = {
                LanguagePicker(
                    shown = shown,
                    query = query,
                    onQueryChange = { query = it },
                    onPick = { language ->
                        picking = false
                        PackService.start(context, state.glossLanguage, language.code)
                    }
                )
            }
        )
    }
}

/**
 * Read from the system rather than from the service's own flag: the setting outlives this
 * process, the flag does not.
 */
private fun lookupEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val name = ComponentName(context, TaplexAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == name }
}
