package de.tieo.taplex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Taplex's screen, as a function of what is true rather than of what it can look up.
 *
 * Everything it needs is in [UiState], so every state it can be in can be drawn without a
 * phone, which is what the book in `docs/model` is made of. A screen that can only be seen
 * by running the app is a screen whose empty and failed states nobody ever looks at.
 *
 * There are two screens here, not one. Until the app can answer anything it shows only what
 * is still to be done, one step at a time; once it can, the setup is gone for good and what
 * is left is a place to look a word up and the few things worth changing.
 */
data class UiState(
    val lookupEnabled: Boolean,
    val canDrawOverlay: Boolean,
    val glossLanguage: String,
    val installed: List<InstalledPack>,
    val build: PackService.State,
    val hoverEnabled: Boolean = false,
    val query: String = "",
    val answer: Explanation? = null,
    val searching: Boolean = false
) {
    /** Nothing is missing: a lookup would work right now. */
    val ready: Boolean get() = lookupEnabled && canDrawOverlay && installed.isNotEmpty()

    /** The language being learned, which is the words language of the pack that is in. */
    val learning: String? get() = installed.firstOrNull()?.wordLanguage
}

/** A dictionary on the phone: which words, explained in what, how big, how many entries. */
data class InstalledPack(
    val wordLanguage: String,
    val glossLanguage: String,
    val bytes: Long,
    val entries: Int = 0
)

/** What the screen can be asked to do. */
data class ScreenActions(
    val onEnableLookup: () -> Unit = {},
    val onAllowOverlay: () -> Unit = {},
    val onAddDictionary: () -> Unit = {},
    val onDeleteDictionary: (InstalledPack) -> Unit = {},
    val onCancelBuild: () -> Unit = {},
    val onHoverChanged: (Boolean) -> Unit = {},
    val onQueryChanged: (String) -> Unit = {},
    val onSearch: () -> Unit = {}
)

@Composable
fun TaplexScreen(state: UiState, actions: ScreenActions = ScreenActions()) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Header(state) }
            if (state.ready) home(state, actions) else setup(state, actions)
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The name, and under it the pair being read, which is the app's whole configuration. */
@Composable
private fun Header(state: UiState) {
    Column(Modifier.padding(top = 28.dp, bottom = 4.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )
        val learning = state.learning
        Text(
            if (learning == null) {
                stringResource(R.string.tagline)
            } else {
                stringResource(
                    R.string.pair,
                    languageName(learning),
                    languageName(state.glossLanguage)
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── before it works ────────────────────────────────────────────────────────────────────

/**
 * What is still to be done, as three steps with one action live at a time.
 *
 * Everything at once is what the screen used to be, and it read as a settings page for an
 * app that had not started yet. A step that is done is a tick and nothing else.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.setup(
    state: UiState,
    actions: ScreenActions
) {
    val steps = listOf(
        Step(R.string.setup_lookup, R.string.setup_lookup_why, state.lookupEnabled, R.string.turn_on),
        Step(R.string.setup_overlay, R.string.setup_overlay_why, state.canDrawOverlay, R.string.turn_on),
        Step(R.string.setup_pack, R.string.setup_pack_why, state.installed.isNotEmpty(), R.string.add)
    )
    val current = steps.indexOfFirst { !it.done }

    item {
        Text(
            stringResource(R.string.setup_lead),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
    }

    when (val build = state.build) {
        is PackService.State.Working -> item { BuildingCard(build, actions.onCancelBuild) }
        is PackService.State.Failed -> item { FailedCard(build) }
        else -> Unit
    }

    itemsIndexed(steps) { index, step ->
        SetupStep(
            number = index + 1,
            title = stringResource(step.title),
            detail = stringResource(step.why),
            done = step.done,
            act = stringResource(step.act),
            live = index == current && state.build !is PackService.State.Working,
            onAct = when (index) {
                0 -> actions.onEnableLookup
                1 -> actions.onAllowOverlay
                else -> actions.onAddDictionary
            }
        )
    }
}

/** One thing still to do before the app can answer anything. */
private data class Step(val title: Int, val why: Int, val done: Boolean, val act: Int)

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    values: List<T>,
    content: @Composable (Int, T) -> Unit
) {
    values.forEachIndexed { index, value -> item { content(index, value) } }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    detail: String,
    done: Boolean,
    act: String,
    live: Boolean,
    onAct: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (live) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Color.Transparent
            }
        ),
        border = if (live) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(
                        when {
                            done -> MaterialTheme.colorScheme.primary
                            live -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (!done) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (live) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = onAct, shape = RoundedCornerShape(12.dp)) { Text(act) }
            }
        }
    }
}

// ── once it works ──────────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.home(
    state: UiState,
    actions: ScreenActions
) {
    item { SearchField(state, actions) }
    state.answer?.let { answer -> item { AnswerCard(answer) } }

    item { SectionHeader(stringResource(R.string.hover_title)) }
    item { HoverCard(state, actions) }

    item { SectionHeader(stringResource(R.string.dictionaries_title)) }
    when (val build = state.build) {
        is PackService.State.Working -> item { BuildingCard(build, actions.onCancelBuild) }
        is PackService.State.Failed -> item { FailedCard(build) }
        else -> Unit
    }
    items(state.installed) { pack ->
        InstalledCard(pack) { actions.onDeleteDictionary(pack) }
    }
    item {
        TextButton(
            onClick = actions.onAddDictionary,
            enabled = state.build !is PackService.State.Working
        ) {
            Text(stringResource(R.string.add_dictionary))
        }
    }

    item { SectionHeader(stringResource(R.string.how_title)) }
    item { Steps() }
}

/** A word can be looked up here too, without arming anything over another app. */
@Composable
private fun SearchField(state: UiState, actions: ScreenActions) {
    OutlinedTextField(
        value = state.query,
        onValueChange = actions.onQueryChanged,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (state.query.isNotBlank()) {
                TextButton(onClick = actions.onSearch) {
                    Text(stringResource(R.string.look_up))
                }
            }
        },
        placeholder = {
            Text(
                stringResource(
                    R.string.look_up_hint,
                    languageName(state.learning ?: state.glossLanguage)
                )
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
}

/** The entry, as the overlay would show it, in the app's own colours. */
@Composable
private fun AnswerCard(answer: Explanation) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (answer.entries.isEmpty()) {
                Text(answer.term, style = MaterialTheme.typography.titleMedium)
                Text(
                    answer.note ?: answer.translation
                        ?: stringResource(R.string.no_entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            answer.entries.forEach { entry -> EntryBlock(entry) }
        }
    }
}

@Composable
private fun EntryBlock(entry: Entry) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(entry.lemma, style = MaterialTheme.typography.titleMedium)
            entry.pos?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            entry.ipa?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        entry.label?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        entry.senses.forEachIndexed { index, sense ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp)
                )
                Column {
                    Row {
                        if (sense.tags.isNotEmpty()) {
                            Text(
                                sense.tags.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(sense.gloss, style = MaterialTheme.typography.bodyMedium)
                    }
                    sense.examples.firstOrNull()?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, top = 12.dp)
    )
}

/**
 * The body of the language picker: a search box over the list of languages.
 *
 * Every language the phone can name is offered, because which of them kaikki has a dump for
 * is only settled by asking for it, and that list is far too long to scroll. Typing is how
 * the one language someone came for is reached, so the box sits above the list and the
 * caller keeps [query], which is what makes the filtered list renderable on its own.
 */
@Composable
fun LanguagePicker(
    shown: List<PackSource.Available>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (PackSource.Available) -> Unit
) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.search_language)) },
            modifier = Modifier.fillMaxWidth()
        )
        if (shown.isEmpty()) {
            Text(
                text = stringResource(R.string.no_language_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, start = 4.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(shown) { language ->
                TextButton(
                    onClick = { onPick(language) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        language.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** A build in flight: what it is doing, how far it has come, and how to stop it. */
@Composable
private fun BuildingCard(working: PackService.State.Working, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        languageName(working.wordLanguage),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(
                            R.string.pack_progress,
                            megabytes(working.bytesRead),
                            megabytes(working.totalBytes),
                            thousands(working.entries)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    percent(working),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (working.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { working.bytesRead.toFloat() / working.totalBytes },
                    trackColor = MaterialTheme.colorScheme.outline,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            } else {
                LinearProgressIndicator(
                    trackColor = MaterialTheme.colorScheme.outline,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            }
            TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun FailedCard(failed: PackService.State.Failed) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.pack_failed, languageName(failed.wordLanguage)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                failed.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** One installed pack: the language, what it holds, and what it cost. */
@Composable
private fun InstalledCard(pack: InstalledPack, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    languageName(pack.wordLanguage),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (pack.entries > 0) {
                        stringResource(
                            R.string.pack_detail_full,
                            thousands(pack.entries),
                            megabytes(pack.bytes)
                        )
                    } else {
                        megabytes(pack.bytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The second way in, for a conversation rather than a page: a circle that follows the
 * finger over one app and answers whatever it passes, without a modal layer.
 */
@Composable
private fun HoverCard(state: UiState, actions: ScreenActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.hover_toggle),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(R.string.hover_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.hoverEnabled, onCheckedChange = actions.onHoverChanged)
        }
    }
}

/** Three steps instead of two paragraphs: what to press, what to tap, what to hold. */
@Composable
private fun Steps() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HowStep(1, stringResource(R.string.step_arm))
        HowStep(2, stringResource(R.string.step_tap))
        HowStep(3, stringResource(R.string.step_say))
        Text(
            stringResource(R.string.records_nothing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp)
        )
    }
}

@Composable
private fun HowStep(number: Int, text: String) {
    Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            modifier = Modifier.width(18.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun languageName(tag: String): String =
    Locale(tag).displayLanguage.ifEmpty { tag }

private fun megabytes(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)

/** Entry counts run to six figures, where the thousands are all anyone reads. */
private fun thousands(count: Int): String =
    if (count >= 1000) "%.0fk".format(count / 1000.0) else count.toString()

private fun percent(working: PackService.State.Working): String =
    if (working.totalBytes <= 0) "" else "${working.bytesRead * 100 / working.totalBytes}%"
