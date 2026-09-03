package de.tieo.wordtap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * WordTap's screen, as a function of what is true rather than of what it can look up.
 *
 * Everything it needs is in [UiState], so every state it can be in can be drawn without a
 * phone, which is what the book in `docs/model` is made of. A screen that can only be seen
 * by running the app is a screen whose empty and failed states nobody ever looks at.
 */
data class UiState(
    val lookupEnabled: Boolean,
    val canDrawOverlay: Boolean,
    val glossLanguage: String,
    val installed: List<InstalledPack>,
    val build: PackService.State
)

/** A dictionary on the phone: which words, explained in what, and how much room it takes. */
data class InstalledPack(
    val wordLanguage: String,
    val glossLanguage: String,
    val bytes: Long
)

/** What the screen can be asked to do. */
data class ScreenActions(
    val onEnableLookup: () -> Unit = {},
    val onAllowOverlay: () -> Unit = {},
    val onAddDictionary: () -> Unit = {},
    val onDeleteDictionary: (InstalledPack) -> Unit = {},
    val onCancelBuild: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordTapScreen(state: UiState, actions: ScreenActions = ScreenActions()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SetupCard(state, actions) }

            item {
                Text(
                    stringResource(R.string.dictionaries_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            when (val build = state.build) {
                is PackService.State.Working -> item { BuildingCard(build, actions.onCancelBuild) }
                is PackService.State.Failed -> item { FailedCard(build) }
                else -> Unit
            }

            if (state.installed.isEmpty() && state.build !is PackService.State.Working) {
                item {
                    Text(
                        stringResource(R.string.no_dictionaries),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            items(state.installed) { pack ->
                InstalledCard(pack) { actions.onDeleteDictionary(pack) }
            }

            item {
                Button(
                    onClick = actions.onAddDictionary,
                    enabled = state.build !is PackService.State.Working
                ) {
                    Text(stringResource(R.string.add_dictionary))
                }
            }

            item {
                Text(
                    stringResource(R.string.explained_in, languageName(state.glossLanguage)),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.how_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.how_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(state: UiState, actions: ScreenActions) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.titleMedium
            )
            PermissionRow(
                label = stringResource(R.string.setup_lookup),
                granted = state.lookupEnabled,
                onFix = actions.onEnableLookup
            )
            PermissionRow(
                label = stringResource(R.string.setup_overlay),
                granted = state.canDrawOverlay,
                onFix = actions.onAllowOverlay
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (granted) {
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.granted))
        } else {
            OutlinedButton(onClick = onFix) { Text(stringResource(R.string.turn_on)) }
        }
    }
}

@Composable
private fun BuildingCard(working: PackService.State.Working, onCancel: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.pack_building, languageName(working.wordLanguage)),
                style = MaterialTheme.typography.titleSmall
            )
            if (working.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { working.bytesRead.toFloat() / working.totalBytes },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                stringResource(
                    R.string.pack_progress,
                    megabytes(working.bytesRead),
                    megabytes(working.totalBytes),
                    working.entries
                ),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun FailedCard(failed: PackService.State.Failed) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.pack_failed, languageName(failed.wordLanguage)),
                style = MaterialTheme.typography.titleSmall
            )
            Text(failed.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InstalledCard(pack: InstalledPack, onDelete: () -> Unit) {
    Card {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    languageName(pack.wordLanguage),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(
                        R.string.pack_detail,
                        languageName(pack.glossLanguage),
                        megabytes(pack.bytes)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

private fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).displayLanguage.ifEmpty { tag }

private fun megabytes(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)
