package com.xlollx.songport.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.BridgePlugin

// ---------------------------------------------------------------------------------------------
// How to: short guides, written into the app (string resources, so offline and translated). An
// article is a few string resources in a light markup: a line starting with "# " is a heading,
// one starting with "• " a bullet, any other line a paragraph.
// ---------------------------------------------------------------------------------------------

enum class HowTo(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    FIRST_SYNC(R.string.howto_first_sync_title, R.string.howto_first_sync_summary, Icons.Filled.PlayCircle),
    MODES(R.string.howto_modes_title, R.string.howto_modes_summary, Icons.Filled.SyncAlt),
    SCHEDULES(R.string.howto_schedules_title, R.string.howto_schedules_summary, Icons.Filled.Schedule),
    PREVIEW(R.string.howto_preview_title, R.string.howto_preview_summary, Icons.Filled.Visibility),
    REVIEW(R.string.howto_review_title, R.string.howto_review_summary, Icons.Filled.Search),
    ORGANISE(R.string.howto_organise_title, R.string.howto_organise_summary, Icons.Filled.Star),
    BACKUPS(R.string.howto_backups_title, R.string.howto_backups_summary, Icons.Filled.Backup),
    TOOLS(R.string.howto_tools_title, R.string.howto_tools_summary, Icons.Filled.Build),
    DISCOVER(R.string.howto_discover_title, R.string.howto_discover_summary, Icons.Filled.Explore),
    SHARED(R.string.howto_shared_title, R.string.howto_shared_summary, Icons.Filled.Groups),
    SERVER(R.string.howto_server_title, R.string.howto_server_summary, Icons.Filled.Dns),
    ACCOUNTS(R.string.howto_accounts_title, R.string.howto_accounts_summary, Icons.Filled.AccountCircle),
    PLUGINS(R.string.howto_plugins_title, R.string.howto_plugins_summary, Icons.Filled.Extension);

    /**
     * The article's text, in order. What differs between the builds is its own part: the Play
     * build describes plugins neutrally (no name, no link), the GitHub/F-Droid build says its
     * web connectors are built in.
     */
    val parts: List<Int>
        get() = when (this) {
            FIRST_SYNC -> listOf(R.string.howto_first_sync_body)
            MODES -> listOf(R.string.howto_modes_body)
            // The battery note is the long text Settings already has for its row.
            // Coordinate with my server: the same syncs on this phone and on a Trackhop server.
            SCHEDULES -> listOf(R.string.howto_schedules_body, R.string.settings_battery_desc, R.string.howto_schedules_body2, R.string.howto_schedules_channels, R.string.server_locks_title, R.string.help_server_locks_body)
            PREVIEW -> listOf(R.string.howto_preview_body)
            REVIEW -> listOf(R.string.howto_review_body)
            BACKUPS -> listOf(R.string.howto_backups_body)
            TOOLS -> listOf(R.string.howto_tools_body, R.string.howto_tools_more)
            ORGANISE -> listOf(R.string.howto_organise_body)
            DISCOVER -> listOf(R.string.howto_discover_body)
            SHARED -> listOf(R.string.howto_shared_body)
            SERVER -> listOf(R.string.howto_server_body)
            ACCOUNTS -> listOfNotNull(
                R.string.howto_accounts_body,
                R.string.howto_accounts_full.takeIf { BridgePlugin.builtIn },
                R.string.howto_accounts_body2,
            )
            PLUGINS -> listOf(R.string.howto_plugins_body, if (BridgePlugin.builtIn) R.string.howto_plugins_full else R.string.howto_plugins_play)
        }
}

/** Parts that are a heading of their own: a setting's name, the same words as on its row. */
private val HEADINGS = setOf(R.string.server_locks_title)

/** One article's body: headings, paragraphs and bullets, as the markup of its parts says. */
@Composable
fun HowToArticle(article: HowTo, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        article.parts.forEach { res ->
            stringResource(res).let { if (res in HEADINGS) "# $it" else it }.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                when {
                    line.startsWith("# ") -> Text(
                        line.removePrefix("# "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                    line.startsWith("• ") -> Row(Modifier.padding(start = 4.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(line.removePrefix("• "), style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Settings › How to: the list of guides and, open, one of them. [initial] opens a guide straight
 * away; back from a guide returns to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowToScreen(onClose: () -> Unit, initial: HowTo? = null, header: (@Composable () -> Unit)? = null) {
    var open by rememberSaveable { mutableStateOf(initial?.name) }
    val article = open?.let { name -> HowTo.entries.firstOrNull { it.name == name } }
    var query by rememberSaveable { mutableStateOf("") }
    // What the search looks through: each guide's title, summary and text, in this build and language.
    val res = LocalContext.current.resources
    val config = LocalConfiguration.current
    val texts = remember(config) {
        HowTo.entries.associateWith { h -> (listOf(h.title, h.summary) + h.parts).joinToString("\n") { res.getString(it) } }
    }
    ScreenBackHandler { if (article != null) open = null else onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(article?.title ?: R.string.howto_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (article != null) open = null else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Lines stay a comfortable length on a tablet or a phone in landscape.
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (article != null) {
                    HowToArticle(article, Modifier.padding(horizontal = 4.dp))
                } else {
                    HowToSearchField(query) { query = it }
                    val shown = HowToSearch.filter(HowTo.entries, query) { texts[it].orEmpty() }
                    if (query.isBlank()) {
                        Text(stringResource(R.string.howto_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                        header?.invoke()
                    }
                    if (shown.isEmpty()) {
                        Text(
                            stringResource(R.string.howto_search_none, query.trim()), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } else {
                        PrefSection(stringResource(R.string.howto_guides)) {
                            shown.forEach { h ->
                                PrefRow(h.icon, stringResource(h.title), stringResource(h.summary)) { open = h.name }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The search at the top of the guides: filters as you type, with a clear button; Search on the keyboard closes it. */
@Composable
private fun HowToSearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        label = { Text(stringResource(R.string.howto_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) {
                Icon(Icons.Filled.Clear, stringResource(R.string.howto_search_clear))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
