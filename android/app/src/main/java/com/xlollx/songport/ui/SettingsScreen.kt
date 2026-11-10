package com.xlollx.songport.ui

import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.GridView
import com.xlollx.songport.ai.config
import com.xlollx.songport.providers.*
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EditNotifications
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StarRate
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.auth.LockActivity
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.BackupExport
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun openUrl(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

internal const val SOURCE_URL = "https://github.com/xlollx/Trackhop"

/** The author attribution of NOTICE (AGPL-3.0 section 7(b)): kept as it is by any modified version. */
internal const val ATTRIBUTION = "Trackhop by xlollx · AGPL-3.0 · github.com/xlollx/Trackhop"

/** The little arrow of a row that leaves the app (browser, system settings). */
@Composable
private fun ExternalMark() = Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))

/** The arrow of a row that opens in place. */
@Composable
private fun ExpandMark(open: Boolean) = Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Settings, in groups read from the top down by how often they are wanted: how the app looks,
 * how it syncs, what it tells you, the backup folder, privacy, then the advanced corner (log,
 * keys, developer tools) and the about box. Every row says its current value under its name.
 */
@Composable
fun SettingsScreen(
    data: StoreData,
    store: Store,
    snackbar: SnackbarHostState,
    onSetup: (MusicProvider) -> Unit,
    onOpenLog: () -> Unit = {},
    onHowTo: () -> Unit = {},
    onBackup: () -> Unit = {},
    onWhatsNew: () -> Unit = {},
    /** Help › Widgets, shortcuts and automation: the "Did you know?" page. */
    onTips: () -> Unit = {},
    /** Asked from that page: scroll to the automation switch and light it up for a moment. */
    focusAutomation: Boolean = false,
    onAutomationFocused: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = data.settings

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ---------------------------------------------------------------- Appearance
        PrefSection(stringResource(R.string.settings_section_appearance), accent = JobColor.PURPLE) {
            val mode = ThemeMode.of(settings.themeMode)
            val themeLabels = ThemeMode.entries.map {
                it to stringResource(when (it) { ThemeMode.SYSTEM -> R.string.settings_theme_system; ThemeMode.LIGHT -> R.string.settings_theme_light; ThemeMode.DARK -> R.string.settings_theme_dark })
            }
            var themeOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.Palette, stringResource(R.string.settings_theme), themeLabels.first { it.first == mode }.second) { themeOpen = true }
            if (themeOpen) ChoiceDialog(stringResource(R.string.settings_theme), themeLabels, mode, onDismiss = { themeOpen = false }) { m ->
                themeOpen = false
                store.updateSettings { it.copy(themeMode = m.name) }
            }
            PrefSwitch(
                Icons.Filled.Wallpaper,
                stringResource(R.string.settings_dynamic_color),
                stringResource(if (dynamicColorAvailable) R.string.settings_dynamic_color_summary else R.string.settings_dynamic_color_unavailable),
                settings.dynamicColor && dynamicColorAvailable, enabled = dynamicColorAvailable,
            ) { v -> store.updateSettings { it.copy(dynamicColor = v) } }
            // How coloured sync cards wear their colour, and how their last runs show.
            val accentChoices = CardAccent.entries.map { it.key to stringResource(it.label) }
            var accentOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.Palette, stringResource(R.string.settings_card_accent), accentChoices.firstOrNull { it.first == settings.cardAccent }?.second ?: accentChoices.first().second) { accentOpen = true }
            if (accentOpen) ChoiceDialog(stringResource(R.string.settings_card_accent), accentChoices, settings.cardAccent, onDismiss = { accentOpen = false }) { k ->
                accentOpen = false
                store.updateSettings { it.copy(cardAccent = k) }
            }
            val placeChoices = listOf("footer" to stringResource(R.string.status_place_footer), "badge" to stringResource(R.string.status_place_badge))
            var placeOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.Palette, stringResource(R.string.settings_status_place), placeChoices.firstOrNull { it.first == settings.cardStatusPlace }?.second ?: placeChoices.first().second) { placeOpen = true }
            if (placeOpen) ChoiceDialog(stringResource(R.string.settings_status_place), placeChoices, settings.cardStatusPlace, onDismiss = { placeOpen = false }) { k ->
                placeOpen = false
                store.updateSettings { it.copy(cardStatusPlace = k) }
            }
            val historyChoices = RunHistoryStyle.entries.map { it.key to stringResource(it.label) }
            var historyOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.Palette, stringResource(R.string.settings_run_history), historyChoices.firstOrNull { it.first == settings.runHistoryStyle }?.second ?: historyChoices.first().second) { historyOpen = true }
            if (historyOpen) ChoiceDialog(stringResource(R.string.settings_run_history), historyChoices, settings.runHistoryStyle, onDismiss = { historyOpen = false }) { k ->
                historyOpen = false
                store.updateSettings { it.copy(runHistoryStyle = k) }
            }
            // How big the sync cards are, each choice with a small picture of how many fit; and the tiles instead of cards.
            val sizeChoices = CardSize.entries.map { it to stringResource(it.label) }
            var sizeOpen by remember { mutableStateOf(false) }
            val size = CardSize.of(settings.cardSize)
            PrefRow(Icons.Filled.Palette, stringResource(R.string.settings_card_size), stringResource(size.label)) { sizeOpen = true }
            if (sizeOpen) ChoiceDialog(
                stringResource(R.string.settings_card_size), sizeChoices, size, onDismiss = { sizeOpen = false },
                note = stringResource(R.string.card_size_landscape), trailing = { CardSizePreview(it) },
            ) { k ->
                sizeOpen = false
                store.updateSettings { it.copy(cardSize = k.key) }
            }
            PrefSwitch(Icons.Filled.GridView, stringResource(R.string.settings_minimal_view), stringResource(R.string.settings_minimal_view_summary), settings.minimalSyncs) { v ->
                store.updateSettings { it.copy(minimalSyncs = v) }
            }
            // Chosen in-app and applied at once (the activity is recreated), also while a sync runs.
            var langOpen by remember { mutableStateOf(false) }
            val currentTag = AppLocale.current(ctx)
            PrefRow(Icons.Filled.Language, stringResource(R.string.settings_language), AppLocale.label(ctx, currentTag)) { langOpen = true }
            if (langOpen) ChoiceDialog(
                stringResource(R.string.settings_language),
                AppLocale.CHOICES.map { it to AppLocale.label(ctx, it) },
                currentTag,
                onDismiss = { langOpen = false },
            ) { tag ->
                langOpen = false
                if (tag != currentTag) (ctx as? Activity)?.let { AppLocale.set(it, tag) }
            }
            PrefSwitch(Icons.Filled.Dashboard, stringResource(R.string.settings_new_look), stringResource(R.string.settings_new_look_summary), settings.newLook, help = { HelpButton(Helps.newLook) }) { v ->
                store.updateSettings { it.copy(newLook = v) }
            }
        }

        // ---------------------------------------------------------------- Syncing
        PrefSection(stringResource(R.string.settings_section_syncing), accent = JobColor.BLUE) {
            PrefSwitch(Icons.Filled.Verified, stringResource(R.string.settings_isrc), stringResource(R.string.settings_isrc_summary), settings.isrcLookup, help = { HelpButton(Helps.isrc) }) { v ->
                store.updateSettings { it.copy(isrcLookup = v) }
            }
            // The link alias lives in the package manager, not in the store: read it from there.
            var musicLinks by remember { mutableStateOf(MusicLinks.enabled(ctx)) }
            PrefSwitch(Icons.Filled.Link, stringResource(R.string.settings_music_links), stringResource(R.string.settings_music_links_summary), musicLinks, help = { HelpButton(Helps.musicLinks) }) { v ->
                MusicLinks.set(ctx, v); musicLinks = v
            }
            PrefRow(
                Icons.Filled.BatteryAlert, stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_summary),
                trailing = { ExternalMark() },
            ) {
                // Apre l'elenco di sistema (nessun permesso speciale): l'utente sceglie Trackhop e lo esclude.
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        // ---------------------------------------------------------------- Notifications
        PrefSection(stringResource(R.string.settings_section_notifications), accent = JobColor.ORANGE) {
            PrefSwitch(Icons.Filled.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_summary), settings.notifyOnSync, help = { HelpButton(Helps.notifications) }) { v ->
                store.updateSettings { it.copy(notifyOnSync = v) }
            }
            // Telegram, ntfy, Discord…: the same channels as Docker and Desktop (core's Notify).
            var channelsOpen by rememberSaveable { mutableStateOf(false) }
            PrefRow(Icons.Filled.Campaign, stringResource(R.string.notify_channels_title), stringResource(R.string.notify_channels_summary)) { channelsOpen = true }
            if (channelsOpen) NotifyChannelsScreen(onClose = { channelsOpen = false })
            // Tasker, MacroDroid…: the commands and the end-of-sync event, off until turned on.
            val automationView = remember { BringIntoViewRequester() }
            val flash = remember { Animatable(0f) }
            LaunchedEffect(focusAutomation) {
                if (!focusAutomation) return@LaunchedEffect
                // Let the tab settle in before scrolling.
                delay(250)
                automationView.bringIntoView()
                // Outside this effect: clearing the request below restarts it, which would cut the flash short.
                scope.launch { repeat(2) { flash.animateTo(1f, tween(220)); flash.animateTo(0f, tween(520)) } }
                onAutomationFocused()
            }
            Box(
                Modifier.bringIntoViewRequester(automationView).then(
                    if (flash.value > 0f) Modifier.border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = flash.value), MaterialTheme.shapes.medium) else Modifier,
                ),
            ) {
                PrefSwitch(Icons.Filled.Bolt, stringResource(R.string.settings_automation), stringResource(R.string.settings_automation_summary), settings.automation) { v ->
                    store.updateSettings { it.copy(automation = v) }
                }
            }
            if (settings.automation) {
                // The intents' names are for whoever sets up Tasker or MacroDroid: folded away, with a copy button.
                var howOpen by rememberSaveable { mutableStateOf(false) }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                    TextButton(onClick = { howOpen = !howOpen }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(R.string.settings_automation_commands))
                        Icon(if (howOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (howOpen) {
                        val how = stringResource(R.string.settings_automation_how)
                        Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(how)) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(stringResource(R.string.settings_automation_copy))
                        }
                    }
                }
            }
            PrefSwitch(Icons.Filled.MusicOff, stringResource(R.string.settings_unplayable), stringResource(R.string.settings_unplayable_summary), settings.unplayableCheck) { v ->
                store.updateSettings { it.copy(unplayableCheck = v) }; Scheduler.applyUnplayable(ctx)
            }
            PrefRow(
                Icons.Filled.EditNotifications, stringResource(R.string.settings_notifications_system), stringResource(R.string.settings_notifications_system_desc),
                trailing = { ExternalMark() },
            ) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
            }
        }

        // ---------------------------------------------------------------- Backup & restore
        // One place for both kinds (BackupScreen): the set-up as a file, and the playlists' backups.
        PrefSection(stringResource(R.string.backup_title), accent = JobColor.GREEN) {
            val folder = settings.backupFolder
            PrefRow(
                Icons.Filled.Backup, stringResource(R.string.backup_title),
                if (folder.isNotBlank()) stringResource(R.string.backup_row_folder, BackupExport.folderLabel(folder)) else stringResource(R.string.backup_row_desc),
            ) { onBackup() }
        }

        // ---------------------------------------------------------------- Privacy and security
        PrefSection(
            stringResource(R.string.settings_section_privacy),
            accent = JobColor.TEAL,
            footer = stringResource(R.string.settings_free_note),
        ) {
            PrefSwitch(Icons.Filled.Lock, stringResource(R.string.security_lock), stringResource(R.string.security_lock_desc), settings.appLock) { v ->
                if (v && !LockActivity.available(ctx)) {
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.security_lock_unavailable)) }
                } else {
                    store.updateSettings { it.copy(appLock = v) }
                }
            }
            var factsOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.Security, stringResource(R.string.security_title), stringResource(R.string.settings_security_summary)) { factsOpen = true }
            if (factsOpen) AlertDialog(
                onDismissRequest = { factsOpen = false },
                title = { Text(stringResource(R.string.security_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(R.string.security_fact_1, R.string.security_fact_2, R.string.security_fact_3, R.string.security_fact_4).forEach {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { factsOpen = false }) { Text(stringResource(R.string.ok)) } },
            )
        }

        // ---------------------------------------------------------------- Plugins
        // Play build only: the GitHub/F-Droid build carries the connectors inside. Neutral on
        // purpose: what a plugin is and which ones are installed, never a name, a link or a hint.
        if (!BridgePlugin.builtIn) {
            // Asking a plugin which services it serves is an IPC call: off the main thread.
            val plugins by produceState<List<Pair<BridgePlugin.Installed, List<String>>>?>(null) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val active = BridgePlugin.packageName(ctx)
                    val services = Providers.services().filter { it.pluginBased && runCatching { it.isConfigured(ctx) }.getOrDefault(false) }.map { it.displayName }
                    BridgePlugin.installedPlugins(ctx).map { it to if (it.packageName == active) services else emptyList() }
                }
            }
            // What a plugin is, above the list; nothing about where plugins come from.
            PrefSection(stringResource(R.string.plugins_title), accent = JobColor.PINK, description = stringResource(R.string.plugins_desc), footer = stringResource(R.string.plugins_where), help = { HelpButton(Helps.plugins) }) {
                val list = plugins
                if (list != null && list.isEmpty()) PrefRow(Icons.Filled.Extension, stringResource(R.string.plugins_none))
                list?.forEach { (p, services) ->
                    val launch = ctx.packageManager.getLaunchIntentForPackage(p.packageName)
                    PrefRow(
                        Icons.Filled.Extension, p.label,
                        listOfNotNull(
                            p.version?.let { stringResource(R.string.settings_version, it) },
                            services.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.plugins_services, it.joinToString(", ")) },
                        ).joinToString(" · "),
                        onClick = launch?.let { i -> { runCatching { ctx.startActivity(i) } } },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- Advanced
        PrefSection(stringResource(R.string.settings_section_advanced)) {
            PrefRow(Icons.Filled.History, stringResource(R.string.settings_log), stringResource(R.string.settings_log_summary), onClick = onOpenLog)
            // Same dialog as the generator in Tools: key and model live in the encrypted token store.
            var aiConfig by remember { mutableStateOf(com.xlollx.songport.ai.AiClient.config(ctx)) }
            var aiOpen by rememberSaveable { mutableStateOf(false) }
            if (aiOpen) AiSetupDialog(aiConfig, onDismiss = { aiOpen = false }) { aiConfig = it; aiOpen = false }
            val ai = aiConfig
            PrefRow(
                Icons.Filled.AutoAwesome, stringResource(R.string.ai_setup_title),
                if (ai != null) "${ai.vendor.label} · ${ai.model}" else stringResource(R.string.not_configured) + " · " + stringResource(R.string.settings_ai_desc),
                trailing = { HelpButton(Helps.ai) },
            ) { aiOpen = true }

            // Coordinate with my server: this phone and the user's Trackhop server take turns on a playlist.
            var serverLocks by remember { mutableStateOf(com.xlollx.songport.sync.ServerLocks.config(com.xlollx.songport.platform.AndroidEnv.of(ctx))) }
            var serverLocksOpen by rememberSaveable { mutableStateOf(false) }
            if (serverLocksOpen) ServerLocksDialog(serverLocks, onDismiss = { serverLocksOpen = false }) { serverLocks = it; serverLocksOpen = false }
            PrefRow(
                Icons.Filled.Dns, stringResource(R.string.server_locks_title),
                serverLocks?.address?.removePrefix("http://")?.removePrefix("https://") ?: stringResource(R.string.server_locks_desc),
                trailing = { HelpButton(Helps.serverLocks) },
            ) { serverLocksOpen = true }

            var keysOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.VpnKey, stringResource(R.string.settings_advanced), stringResource(R.string.settings_keys_summary), trailing = { ExpandMark(keysOpen) }) { keysOpen = !keysOpen }
            if (keysOpen) {
                PrefBlock { Text(stringResource(R.string.settings_advanced_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                // Concerts (Discover): the user's own Ticketmaster key, optional.
                var tmOpen by rememberSaveable { mutableStateOf(false) }
                if (tmOpen) TicketmasterKeyDialog { tmOpen = false }
                PrefRow(Icons.Filled.Key, stringResource(R.string.concerts_key_title), stringResource(remember(data.settings, tmOpen) { ticketmasterKeyState(ctx) })) { tmOpen = true }
                // Setlists (Discover) and setlist.fm links: the user's own setlist.fm key.
                var sfmOpen by rememberSaveable { mutableStateOf(false) }
                if (sfmOpen) SetlistFmKeyDialog { sfmOpen = false }
                PrefRow(Icons.Filled.Key, stringResource(R.string.setlists_key_title), stringResource(remember(data.settings, sfmOpen) { setlistFmKeyState(ctx) })) { sfmOpen = true }
                // Records and merch (Discover): the user's own Discogs token, optional.
                var dgOpen by rememberSaveable { mutableStateOf(false) }
                if (dgOpen) DiscogsTokenDialog { dgOpen = false }
                PrefRow(Icons.Filled.Key, stringResource(R.string.records_token_title), stringResource(remember(dgOpen) { discogsTokenState(ctx) })) { dgOpen = true }
                Providers.all().filter { it.setupGuide != null && it.slot.isEmpty() }.forEach { p ->
                    val configured = remember(p.id, data.settings) { p.isConfigured(ctx) }
                    PrefRow(
                        null, p.displayName,
                        stringResource(if (configured) R.string.setup_using_own else R.string.not_configured),
                        leading = { ProviderBadge(p, 40.dp) },
                        trailing = {
                            Text(stringResource(if (configured) R.string.edit else R.string.setup_now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        },
                    ) { onSetup(p) }
                }
            }

            PrefSwitch(Icons.Filled.DeveloperMode, stringResource(R.string.settings_developer_switch), stringResource(R.string.settings_developer_summary), settings.developerOptions) { v ->
                store.updateSettings { it.copy(developerOptions = v) }
            }
            if (settings.developerOptions && settings.captureUnlocked && com.xlollx.songport.providers.BuiltIn.captureIntent(ctx, "https://x") != null) {
                // A service being added (Qobuz…): its web player opens here and its calls are written
                // to a file to share, without cookies, tokens or passwords.
                var captureOpen by rememberSaveable { mutableStateOf(false) }
                PrefRow(Icons.Filled.NetworkCheck, stringResource(R.string.capture_site_title), stringResource(R.string.capture_site_desc)) { captureOpen = true }
                if (captureOpen) {
                    var address by rememberSaveable { mutableStateOf("https://play.qobuz.com") }
                    AlertDialog(
                        onDismissRequest = { captureOpen = false },
                        title = { Text(stringResource(R.string.capture_site_title)) },
                        text = { OutlinedTextField(address, { address = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.capture_site_address)) }) },
                        confirmButton = {
                            TextButton(enabled = address.startsWith("https://") && address.length > 10, onClick = {
                                captureOpen = false
                                com.xlollx.songport.providers.BuiltIn.captureIntent(ctx, address)?.let { runCatching { ctx.startActivity(it) } }
                            }) { Text(stringResource(R.string.capture_site_open)) }
                        },
                        dismissButton = { TextButton(onClick = { captureOpen = false }) { Text(stringResource(android.R.string.cancel)) } },
                    )
                }
            }
            if (settings.developerOptions) {
                // "Nothing leaves your phone except…" made checkable: the hosts this app has talked to.
                var connectionsOpen by remember { mutableStateOf(false) }
                var connectionsVersion by remember { mutableIntStateOf(0) }
                PrefRow(Icons.Filled.NetworkCheck, stringResource(R.string.security_connections), stringResource(R.string.security_connections_desc), trailing = { ExpandMark(connectionsOpen) }) {
                    connectionsOpen = !connectionsOpen
                }
                if (connectionsOpen) PrefBlock {
                    val entries = remember(connectionsVersion) { com.xlollx.songport.net.HostLog.entries() }
                    if (com.xlollx.songport.providers.BuiltIn.available) Text(stringResource(R.string.security_connections_web), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entries.isEmpty()) Text(stringResource(R.string.security_connections_none), style = MaterialTheme.typography.bodySmall)
                    entries.forEach { e ->
                        Column {
                            Text(e.host, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.security_connections_count, e.count, formatDate(e.lastAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (entries.isNotEmpty()) TextButton(onClick = { com.xlollx.songport.net.HostLog.clear(); connectionsVersion++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.security_connections_clear))
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Help
        // The guides, written into the app: offline, and in the app's language.
        PrefSection(stringResource(R.string.settings_section_help)) {
            PrefRow(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.howto_title), stringResource(R.string.howto_summary), onClick = onHowTo)
            PrefRow(Icons.Filled.Widgets, stringResource(R.string.settings_tips_row), stringResource(R.string.settings_tips_row_summary), onClick = onTips)
            // Ideas, as a GitHub issue (public, voted) or an email (private): see IdeaSheet.
            var ideaOpen by rememberSaveable { mutableStateOf(false) }
            PrefRow(Icons.Filled.Lightbulb, stringResource(R.string.idea_row), stringResource(R.string.idea_row_summary)) { ideaOpen = true }
            if (ideaOpen) IdeaSheet(onDismiss = { ideaOpen = false })
        }

        // ---------------------------------------------------------------- About
        // Store builds (Play, AppGallery) never point to payments outside the store.
        val supportNote = if (!BuildConfig.STORE_BUILD && BuildConfig.KOFI_URL.isNotBlank()) stringResource(R.string.support_body) else null
        PrefSection(stringResource(R.string.settings_about), footer = supportNote) {
            // Seven taps on the version show the traffic captures (with developer options on): a tool
            // for the developer, which records what is done on a site, kept out of everyone's way.
            var versionTaps by remember { mutableIntStateOf(0) }
            // A long press on the logo shows Oli (Oli.kt): its own gesture, apart from the seven taps.
            var oliOpen by remember { mutableStateOf(false) }
            fun versionTap() {
                versionTaps++
                if (versionTaps >= 7) {
                    versionTaps = 0
                    val already = settings.captureUnlocked
                    if (!already) store.updateSettings { it.copy(captureUnlocked = true, developerOptions = true) }
                    android.widget.Toast.makeText(ctx, ctx.getString(if (already) R.string.capture_already_unlocked else R.string.capture_unlocked), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            PrefRow(
                null, stringResource(R.string.app_name), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                leading = {
                    Box(Modifier.combinedClickable(onClick = { versionTap() }, onLongClick = { oliOpen = true })) { BrandMark(40.dp) }
                },
                chevron = false,
                onClick = { versionTap() },
            )
            if (oliOpen) OliDialog { oliOpen = false }
            // The GitHub build's update check (the Play build follows Google Play): an arrow in the top bar.
            if (com.xlollx.songport.update.AppUpdates.configurable) {
                var updates by remember { mutableStateOf(com.xlollx.songport.update.AppUpdates.enabled(ctx)) }
                val updatesScope = rememberCoroutineScope()
                // Installed by F-Droid, Obtainium…: that app tells about updates, so no arrow; said here.
                val updater = remember { com.xlollx.songport.update.AppUpdates.updatedBy(ctx) }
                PrefSwitch(
                    Icons.Filled.Upgrade, stringResource(R.string.settings_update_check),
                    if (updater != null) stringResource(R.string.settings_update_check_elsewhere, updater) else stringResource(R.string.settings_update_check_summary),
                    updates && updater == null, enabled = updater == null,
                ) { v ->
                    updates = v
                    com.xlollx.songport.update.AppUpdates.setEnabled(ctx, v); com.xlollx.songport.sync.Scheduler.applyUpdateCheck(ctx)
                    if (v) updatesScope.launch { com.xlollx.songport.update.AppUpdates.check(ctx, force = true) }
                }
            }
            // The newest release's highlights, the sheet shown once after the update.
            PrefRow(Icons.Filled.NewReleases, stringResource(R.string.whats_new_row), stringResource(R.string.whats_new_row_summary, WhatsNew.newestVersion), onClick = onWhatsNew)
            // Open source: the repository holds the code, the docs and the connector-plugin page. The line
            // under it is the author attribution the license's additional terms (NOTICE) ask forks to keep.
            PrefRow(Icons.Filled.Code, stringResource(R.string.settings_source_code), ATTRIBUTION, trailing = { ExternalMark() }) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
            }
            // Store builds have no Ko-fi row: the site is where anyone who wants can find more (and support).
            if (BuildConfig.STORE_BUILD) {
                PrefRow(Icons.Filled.Language, stringResource(R.string.settings_website), "trackhop.org", trailing = { ExternalMark() }) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://trackhop.org"))) }
                }
            }
            PrefRow(Icons.Filled.Policy, stringResource(R.string.settings_privacy_policy), null, trailing = { ExternalMark() }) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }
            }
            // Rate where the app came from (Play, AppGallery); share a link to it with anyone.
            if (com.xlollx.songport.update.AppUpdates.rateInStore) {
                PrefRow(Icons.Filled.StarRate, stringResource(R.string.settings_rate), stringResource(R.string.settings_rate_summary), trailing = { ExternalMark() }) {
                    com.xlollx.songport.update.AppUpdates.open(ctx)
                }
            }
            PrefRow(Icons.Filled.Share, stringResource(R.string.settings_share_app), null) {
                val text = ctx.getString(R.string.settings_share_app_text, com.xlollx.songport.update.AppUpdates.shareUrl(ctx))
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
            }
            PrefRow(Icons.Filled.Replay, stringResource(R.string.settings_show_onboarding), null) {
                store.updateSettings { it.copy(onboardingDone = false) }
            }
            // One row: Ko-fi or GitHub Sponsors, chosen in the sheet it opens.
            if (!BuildConfig.STORE_BUILD && (BuildConfig.KOFI_URL.isNotBlank() || BuildConfig.SPONSORS_URL.isNotBlank())) {
                PrefRow(Icons.Filled.Favorite, stringResource(R.string.support_row), null) { openSupport(ctx) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
