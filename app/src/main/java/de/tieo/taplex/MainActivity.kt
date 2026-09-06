package de.tieo.taplex

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        setContent { TaplexTheme { Main() } }
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
    var everywhere by remember { mutableStateOf(prefs.hoverEverywhere) }
    var onRight by remember { mutableStateOf(prefs.markOnRight) }
    var chosen by remember { mutableStateOf(prefs.hoverPackages) }
    // Every app with a launcher entry, which is what a reader means by "an app". Read off
    // the main thread: a phone with a few hundred of them takes a moment over it.
    var apps by remember { mutableStateOf<List<AppChoice>>(emptyList()) }
    LaunchedEffect(hovering, everywhere) {
        if (hovering && !everywhere && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) { launcherApps(context) }
        }
    }
    var query by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<Explanation?>(null) }
    val lookup = remember { Lookup(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(build) { reread++ }

    // Both permissions are granted on a screen of the system's, not here, so the only
    // moment the answer can have changed is when this comes back to the front. Without
    // this the step someone just finished still says it is waiting for them.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reread++
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }

    val state = remember(reread, build, hovering, everywhere, onRight, chosen, apps, query, answer) {
        UiState(
            lookupEnabled = lookupEnabled(context),
            canDrawOverlay = Settings.canDrawOverlays(context),
            glossLanguage = prefs.targetLanguage,
            installed = Dictionary.installed(context).map { (gloss, word) ->
                InstalledPack(
                    wordLanguage = word,
                    glossLanguage = gloss,
                    bytes = Dictionary.file(context, gloss, word).length(),
                    entries = Dictionary.entryCount(context, gloss, word)
                )
            },
            build = build,
            hoverEnabled = hovering,
            hoverEverywhere = everywhere,
            markOnRight = onRight,
            apps = apps.map { it.copy(chosen = it.pkg in chosen) },
            query = query,
            answer = answer
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
            onQueryChanged = { typed ->
                query = typed
                if (typed.isBlank()) answer = null
            },
            onSearch = {
                val term = query.trim()
                if (term.isNotEmpty()) {
                    scope.launch { answer = lookup.explain(term) }
                }
            },
            onAddTile = {
                // The tile is the trigger that needs nothing else turned on. Asking for it
                // here is the only way to add one without walking someone through editing
                // their quick settings by hand.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.getSystemService(StatusBarManager::class.java)
                        ?.requestAddTileService(
                            ComponentName(context, TaplexTileService::class.java),
                            context.getString(R.string.tile_label),
                            Icon.createWithResource(context, R.drawable.ic_tile),
                            {},
                            {}
                        )
                }
            },
            onHoverChanged = { wanted ->
                prefs.hoverEnabled = wanted
                hovering = wanted
            },
            onHoverEverywhereChanged = { wanted ->
                prefs.hoverEverywhere = wanted
                everywhere = wanted
            },
            onMarkSideChanged = { right ->
                prefs.markOnRight = right
                onRight = right
                // The mark moves to the side it was just given rather than at the end of
                // the next drag, since the point of choosing was to know where it is.
                TaplexAccessibilityService.running?.repark()
            },
            onHoverAppToggled = { pkg ->
                val next = if (pkg in chosen) chosen - pkg else chosen + pkg
                prefs.hoverPackages = next
                chosen = next
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

/** Everything with a launcher entry, which is what a reader thinks of as "an app". */
private fun launcherApps(context: Context): List<AppChoice> {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .mapNotNull { found ->
            val pkg = found.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            AppChoice(pkg, found.loadLabel(pm)?.toString() ?: pkg, chosen = false)
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}
