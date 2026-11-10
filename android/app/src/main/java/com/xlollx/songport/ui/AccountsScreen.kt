package com.xlollx.songport.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.xlollx.songport.providers.*
import android.net.Uri
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.providers.YouTubeProvider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import com.xlollx.songport.data.Store
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.auth.AuthEvents
import com.xlollx.songport.auth.AuthFlow
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.BridgePlugin
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connectors, not services: the screen starts empty and the user adds one entry per account they
 * want to use ("Spotify", "Spotify 2", "Files"...), each with its own name. Several connectors of
 * the same service are separate accounts; Files allows one.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(snackbar: SnackbarHostState, showAdd: Boolean, onShowAdd: (Boolean) -> Unit, onSetup: (MusicProvider) -> Unit, onManageFiles: () -> Unit = {}, onSendTo: (id: String, name: String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Ricalcola lo stato connesso/non connesso quando si torna dal browser (login) o dopo logout.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    // Il login loopback (Google) finisce fuori da ogni Activity: ci avvisa da qui.
    val authVersion by AuthEvents.version.collectAsState()
    LaunchedEffect(authVersion) { refresh++ }

    var connectFor by remember { mutableStateOf<MusicProvider?>(null) }
    connectFor?.let { p ->
        ConnectDialog(p, onDismiss = { connectFor = null }) {
            connectFor = null
            AuthFlow.start(ctx, p)
        }
    }

    // "+": pick a service, then name the connector.
    var naming by remember { mutableStateOf<MusicProvider?>(null) }
    if (showAdd) PickServiceDialog(
        connectors = data.settings.connectors,
        onDismiss = { onShowAdd(false) },
        onPick = { service -> onShowAdd(false); naming = service },
    )
    naming?.let { service ->
        val n = data.settings.connectors.count { Providers.byId(it)?.serviceId == service.serviceId } + 1
        NameDialog(
            title = stringResource(R.string.connector_add),
            initial = if (n == 1) service.displayName else "${service.displayName} $n",
            onDismiss = { naming = null },
        ) { name ->
            naming = null
            addConnector(store, service, name)
        }
    }
    var renaming by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    renaming?.let { p ->
        NameDialog(stringResource(R.string.connector_rename), p.label(ctx), onDismiss = { renaming = null }) { name ->
            renaming = null
            store.updateSettings { s -> s.copy(connectorNames = s.connectorNames + (p.id to name)) }
        }
    }

    fun remove(p: MusicProvider) {
        val inUse = data.jobs.any { it.source.provider == p.id || it.target.provider == p.id }
        if (inUse) {
            scope.launch { snackbar.showSnackbar(ctx.getString(R.string.account_remove_blocked)) }
            return
        }
        if (p.requiresAuth) runCatching { p.disconnect(ctx) }
        store.updateSettings { s ->
            s.copy(
                connectors = s.connectors.filter { it != p.id },
                connectorNames = s.connectorNames - p.id,
                extraAccounts = if (p.slot.isEmpty()) s.extraAccounts
                else s.extraAccounts + (p.serviceId to s.extraAccounts[p.serviceId].orEmpty().filter { it != p.slot }),
            )
        }
    }

    // Reading the settings here subscribes this scope to the store: a new connector shows up at once.
    val providers = remember(data.settings.connectors, data.settings.extraAccounts, data.settings.connectorNames) {
        data.settings.connectors.mapNotNull { Providers.byId(it) }
    }
    if (providers.isEmpty()) {
        EmptyState(Icons.Filled.AccountCircle, stringResource(R.string.connectors_empty_title), stringResource(R.string.connectors_empty_body))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp + LocalBottomBarInset.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "intro") { TrustBanner() }
        items(providers, key = { it.id }) { p ->
            key(refresh) {
                if (p.serviceId == LocalFilesProvider.serviceId) FilesCard(snackbar, onRemove = { remove(p) }, onManage = onManageFiles, onSendTo = onSendTo)
                // A source with nothing to sign in to (the charts): its name, what it is, remove.
                else if (!p.requiresAuth) PlainSourceCard(p, onRemove = { remove(p) })
                else ProviderCard(
                    p = p,
                    // Read here, where the connector names are observed: the card itself is skipped
                    // when only the name changes (same provider), and a rename would not show.
                    label = p.label(ctx),
                    quotaUsed = if (p.serviceId == YouTubeProvider.SERVICE) QuotaMeter.used(data, YouTubeProvider.SERVICE) else null,
                    onSetup = onSetup,
                    onConnect = { connectFor = p },
                    onRename = { renaming = p },
                    onRemove = { remove(p) },
                    onChanged = { refresh++ },
                    onMessage = { m -> scope.launch { snackbar.showSnackbar(m) } },
                )
            }
        }
    }
}

/**
 * Adds a connector of [service] named [name] and returns its id: the first connector of a service
 * takes the primary slot, the next ones a numbered slot.
 */
internal fun addConnector(store: Store, service: MusicProvider, name: String): String {
    var id = ""
    store.updateSettings { s ->
        val primaryTaken = service.serviceId in s.connectors
        val slot = if (!primaryTaken) "" else {
            val used = s.extraAccounts[service.serviceId].orEmpty()
            ((used.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 1) + 1).toString()
        }
        id = if (slot.isEmpty()) service.serviceId else "${service.serviceId}@$slot"
        s.copy(
            connectors = s.connectors + id,
            connectorNames = s.connectorNames + (id to name),
            extraAccounts = if (slot.isEmpty()) s.extraAccounts
            else s.extraAccounts + (service.serviceId to s.extraAccounts[service.serviceId].orEmpty() + slot),
        )
    }
    return id
}

/** The services offered to add, in display order: plugin-based ones only where this build may name them or a plugin is installed. */
internal fun addableServices(ctx: android.content.Context): List<MusicProvider> =
    Providers.services().filter { !it.linkOnly && (!it.pluginBased || BuildConfig.PLUGIN_LINKS || it.isConfigured(ctx)) }

/** Service picker for a new connector. Services that allow one connector only are greyed out once added. */
@Composable
private fun PickServiceDialog(connectors: List<String>, onDismiss: () -> Unit, onPick: (MusicProvider) -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_pick_title)) },
        text = {
            // Plugin-based services are offered only when a plugin is installed, unless this build
            // may point to where to get one.
            val services = addableServices(ctx)
            // A service reachable two ways (its official API with a key of yours, or its web player with a
            // plain sign-in) is one entry with two routes, each saying what it asks and what it is.
            val families = services.groupBy { it.familyName }
            // First a grid of the services, grouped; a service reachable two ways then shows its
            // routes, each saying what it asks and what it is.
            var family by remember { mutableStateOf<String?>(null) }
            val chosen = family?.let { families[it] }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (chosen == null) {
                    serviceGroups(families.keys.toList(), families).forEach { (groupRes, names) ->
                        if (names.isEmpty()) return@forEach
                        Text(stringResource(groupRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            names.forEach { name ->
                                val members = families.getValue(name)
                                val allTaken = members.all { m -> !m.supportsMultipleAccounts && connectors.any { Providers.byId(it)?.serviceId == m.serviceId } }
                                // A web connector that would take another account but cannot here opens
                                // its route, where the line under it says why (see WebAccounts).
                                val explained = allTaken && members.any { WebAccounts.reason(ctx, it) != null }
                                ServiceGridTile(members.first(), name, enabled = !allTaken || explained) {
                                    if (members.size == 1 && !explained) onPick(members.first()) else family = name
                                }
                            }
                        }
                    }
                    // Play build: the services this version cannot reach on its own, greyed out under
                    // a plain fact. Nothing to tap, no name of anything else, no link.
                    // A service a plugin brings (an account, or the reading of its public links) is not one of them.
                    val offered = services.map { it.serviceId }.flatMap { listOf(it, it.removeSuffix("_web")) }.toSet()
                    val unavailable = if (BridgePlugin.builtIn) emptyList() else UNAVAILABLE_ON_PLAY.filter { (id, _, _) ->
                        id !in offered && Providers.byId(id)?.let { it.linkOnly && it.isConfigured(ctx) } != true
                    }
                    if (unavailable.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Text(stringResource(R.string.services_group_unavailable), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            HelpButton(Helps.unavailable)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            unavailable.forEach { (id, color, name) -> UnavailableServiceTile(id, color, name) }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { family = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) }
                        ServiceLogo(chosen.first(), 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(family!!, style = MaterialTheme.typography.titleMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.connector_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                        HelpButton(Helps.routes)
                    }
                    // The easiest route first, so a first-timer's eye lands on it.
                    chosen.sortedBy { if (it.route == MusicProvider.Route.EASY) 0 else 1 }.forEach { s ->
                        val taken = !s.supportsMultipleAccounts && connectors.any { Providers.byId(it)?.serviceId == s.serviceId }
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = !taken) { onPick(s) }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    s.route?.let { r ->
                                        // Both routes wear the lock: the quick one signs in on the service's own page too.
                                        StatusPill(stringResource(if (r == MusicProvider.Route.EASY) R.string.route_easy else R.string.route_official), if (r == MusicProvider.Route.EASY) Tone.Ok else Tone.Accent, Icons.Filled.Lock)
                                    } ?: Text(s.displayName, style = MaterialTheme.typography.bodyLarge)
                                    if (!s.canWrite) Text(stringResource(R.string.read_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (s.alpha || s.beta) Text(stringResource(if (s.alpha) R.string.alpha else R.string.beta), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (taken) Text(stringResource(R.string.connected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                s.routeNoteRes?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                // A web connector that would take another account, but cannot here: why, in a line.
                                if (taken) WebAccounts.reason(ctx, s)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        // No link out: what plugins are is explained, neutrally, in Settings › Plugins (Play build).
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.connector_name_label)) }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.help_connector_name)) },
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TrustBanner() {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.accounts_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ProviderCard(
    p: MusicProvider,
    label: String,
    quotaUsed: Long?,
    onSetup: (MusicProvider) -> Unit,
    onConnect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onChanged: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    // Letture dal token cifrato: una volta per scheda, non a ogni ridisegno (key(refresh) le rinnova).
    val configured = remember(p.id) { p.isConfigured(ctx) }
    val connected = remember(p.id) { p.isConnected(ctx) }
    val account = remember(p.id) { p.accountName(ctx) }
    // The edge tells the state at a glance: the success tone when connected, a hairline otherwise.
    val edge = if (connected) Tones.onSuccessContainer().copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, edge),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceLogo(p, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label + (if (!p.canWrite) " · " + stringResource(R.string.read_only) else "") +
                            (if (p.alpha) " · " + stringResource(R.string.alpha) else if (p.beta) " · " + stringResource(R.string.beta) else ""),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            connected -> stringResource(R.string.connected_as, account ?: p.displayName)
                            p.slot.isEmpty() -> stringResource(p.noteRes)
                            else -> stringResource(R.string.not_connected)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (connected) StatusPill(stringResource(R.string.connected), Tone.Ok, Icons.Filled.CheckCircle)
                else if (!configured) StatusPill(stringResource(R.string.not_configured), Tone.Neutral)
            }
            if (connected && quotaUsed != null) QuotaCard(used = quotaUsed, sharedCredentials = !p.usesOwnCredentials(ctx))
            // Detto una volta, dove si sceglie di usarlo: meglio qui che come sorpresa a sync finita.
            if (p.alpha || p.beta) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(if (p.alpha) R.string.alpha_note else R.string.beta_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (p.usesOwnCredentials(ctx)) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.setup_using_own), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!configured && !connected) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(p.notConfiguredRes ?: R.string.not_configured_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (connected) {
                    Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.account_encrypted), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                } else Spacer(Modifier.weight(1f))
                var testing by remember { mutableStateOf(false) }
                if (testing) ConnectionTestDialog(p) { testing = false }
                // YouTube Music: the channel the account acts as (its own, or a brand account), where the connectors offer it.
                var choosingChannel by remember { mutableStateOf(false) }
                val channels = p is YouTubeBridgeProvider && connected && remember(p.id) { p.channelsSupported(ctx.env) }
                if (choosingChannel && p is YouTubeBridgeProvider) YtmChannelDialog(p, onDismiss = { choosingChannel = false }) { choosingChannel = false; onChanged() }
                var startingOver by remember { mutableStateOf(false) }
                val cardScope = rememberCoroutineScope()
                if (startingOver && p is com.xlollx.songport.providers.StartsOver) AlertDialog(
                    onDismissRequest = { startingOver = false },
                    title = { Text(stringResource(R.string.start_over_title, p.label(ctx))) },
                    text = { Text(stringResource(R.string.start_over_body, p.displayName)) },
                    confirmButton = {
                        TextButton(onClick = {
                            startingOver = false
                            cardScope.launch {
                                kotlinx.coroutines.withContext(Dispatchers.IO) { p.startOver(ctx.env) }
                                onMessage(ctx.getString(R.string.start_over_done, p.label(ctx)))
                                onChanged()
                            }
                        }) { Text(stringResource(R.string.start_over)) }
                    },
                    dismissButton = { TextButton(onClick = { startingOver = false }) { Text(stringResource(R.string.cancel)) } },
                )
                val actions = listOfNotNull(
                    if (connected) MenuAction(stringResource(R.string.account_test), { testing = true }) else null,
                    if (channels) MenuAction(stringResource(R.string.ytm_channel), { choosingChannel = true }) else null,
                    if (p.setupGuide != null && p.slot.isEmpty() && configured) MenuAction(stringResource(R.string.setup_cta), { onSetup(p) }) else null,
                    if (connected && p.revokeUrl != null) MenuAction(stringResource(R.string.account_revoke, p.displayName), {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.revokeUrl)))
                    }) else null,
                    // A web connector starts over: signed out, its sites' data forgotten (connected or not:
                    // a site can remember a sign-in the connector never got, #53).
                    if (p is com.xlollx.songport.providers.StartsOver) MenuAction(stringResource(R.string.start_over), { startingOver = true }) else null,
                    MenuAction(stringResource(R.string.connector_rename), onRename),
                    MenuAction(stringResource(R.string.account_remove), onRemove, destructive = true),
                ).let { base ->
                    // Built-in web connectors: Google's block page, the Amazon traffic capture.
                    com.xlollx.songport.providers.BuiltIn.extraActions(ctx, p.serviceId, com.xlollx.songport.data.Store.get(ctx).data.settings.let { it.developerOptions && it.captureUnlocked }).map { (label, intent) ->
                        MenuAction(label, { ctx.startActivity(intent) })
                    } + base
                }
                OverflowMenu(actions)
                when {
                    connected -> OutlinedButton(onClick = { p.disconnect(ctx); onChanged() }) { Text(stringResource(R.string.disconnect)) }
                    // Servizio che passa da un'app compagna non ancora installata: il pulsante la scarica.
                    !configured && p.installUrl != null -> Button(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.installUrl)))
                    }) { Text(stringResource(R.string.ytm_install)) }
                    // Nessuna chiave per questo servizio: il pulsante principale porta alla procedura guidata.
                    !configured && p.setupGuide != null -> Button(onClick = { onSetup(p) }) { Text(stringResource(R.string.setup_now)) }
                    else -> Button(enabled = configured, onClick = onConnect) { Text(stringResource(R.string.connect)) }
                }
            }
        }
    }
}

/** File playlists: import, paste, export, delete. Shown in Tools always and in Accounts when the File connector is added. */
@Composable
internal fun FilesCard(
    snackbar: SnackbarHostState,
    onRemove: (() -> Unit)? = null,
    onManage: () -> Unit = {},
    onSendTo: (id: String, name: String) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { lists = withContext(Dispatchers.IO) { LocalFilesProvider.playlists(ctx) } }

    // "Send to a service" right after the import: the list is there, where should it go?
    fun offerSend(id: String, name: String, tracks: Int) {
        scope.launch {
            val res = snackbar.showSnackbar(
                message = ctx.getString(R.string.csv_imported, tracks),
                actionLabel = ctx.getString(R.string.files_send_to),
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) onSendTo(id, name)
        }
    }

    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }
    LaunchedEffect(pendingImport) {
        val uri = pendingImport ?: return@LaunchedEffect
        pendingImport = null
        // In the card's scope: clearing pendingImport restarts this effect, which would cancel the import.
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Bytes, not a UTF-8 reader: a BOM or a Windows-1252 .m3u decodes as written.
                    val text = ctx.contentResolver.openInputStream(uri)?.use { com.xlollx.songport.sync.PlaylistFiles.decode(it.readBytes()) } ?: ""
                    LocalFilesProvider.importFileAsPlaylist(ctx, displayName(ctx, uri), text)
                }
            }
            onMain {
                result.onSuccess { pl ->
                    version++
                    offerSend(pl.id, pl.name, pl.trackCount.coerceAtLeast(0))
                }.onFailure { snackbar.showSnackbar(it.message ?: ctx.getString(R.string.error_generic)) }
            }
        }
    }

    // How "A - B - C" names in imported files are read (#49).
    var namesOpen by remember { mutableStateOf(false) }
    var patternsOpen by remember { mutableStateOf(false) }
    if (patternsOpen) {
        val store = remember { Store.get(ctx) }
        FilePatternsDialog(
            initial = store.state.value.settings.fileNamePatterns,
            onDismiss = { patternsOpen = false },
            onSave = { lines ->
                patternsOpen = false
                store.updateSettings { it.copy(fileNameLayout = com.xlollx.songport.sync.PlaylistFiles.NameLayout.CUSTOM.name, fileNamePatterns = lines) }
                scope.launch { snackbar.showSnackbar(ctx.getString(R.string.files_names_saved)) }
            },
        )
    }
    if (namesOpen) {
        val store = remember { Store.get(ctx) }
        val settings by store.state.collectAsState()
        ChoiceDialog(
            title = stringResource(R.string.files_names),
            options = listOf(
                com.xlollx.songport.sync.PlaylistFiles.NameLayout.AUTO to stringResource(R.string.files_names_auto),
                com.xlollx.songport.sync.PlaylistFiles.NameLayout.ARTIST_TITLE to stringResource(R.string.files_names_two),
                com.xlollx.songport.sync.PlaylistFiles.NameLayout.ARTIST_ALBUM_TITLE to stringResource(R.string.files_names_three),
                com.xlollx.songport.sync.PlaylistFiles.NameLayout.CUSTOM to stringResource(R.string.files_names_custom),
            ),
            selected = com.xlollx.songport.sync.PlaylistFiles.NameLayout.of(settings.settings.fileNameLayout),
            onDismiss = { namesOpen = false },
            onSelect = { v ->
                namesOpen = false
                // Own patterns: chosen once they are written (and editable each time it is picked).
                if (v == com.xlollx.songport.sync.PlaylistFiles.NameLayout.CUSTOM) { patternsOpen = true; return@ChoiceDialog }
                store.updateSettings { it.copy(fileNameLayout = v.name) }
                scope.launch { snackbar.showSnackbar(ctx.getString(R.string.files_names_saved)) }
            },
        )
    }

    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    if (pasteOpen) PasteListDialog(
        onDismiss = { pasteOpen = false },
        onImport = { name, text ->
            pasteOpen = false
            scope.launch {
                try {
                    val link = com.xlollx.songport.sync.SetlistImport.linkIn(text)
                    val pl = if (link != null) {
                        val s = com.xlollx.songport.sync.SetlistImport.fetch(com.xlollx.songport.platform.AndroidEnv.of(ctx), link)
                        val id = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(ctx, if (name.isBlank()) s.name else name, s.tracks) }
                        Playlist(id, id, s.tracks.size)
                    } else withContext(Dispatchers.IO) { LocalFilesProvider.importFileAsPlaylist(ctx, "$name.txt", text) }
                    onMain {
                        version++
                        // A pasted list, like an imported file, can go straight to a service.
                        offerSend(pl.id, pl.name, pl.trackCount.coerceAtLeast(0))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    onMain { snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic)) }
                }
            }
        },
    )

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(LocalFilesProvider, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(LocalFilesProvider.label(ctx), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OverflowMenu(listOfNotNull(
                    MenuAction(stringResource(R.string.files_names), { namesOpen = true }),
                    onRemove?.let { MenuAction(stringResource(R.string.account_remove), it, destructive = true) },
                ))
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(LocalFilesProvider.noteRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The how-to only while there is nothing yet: once files exist, the list speaks for itself.
            if (lists.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.csv_playlists_hint), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            if (lists.isEmpty()) Text(stringResource(R.string.csv_empty), style = MaterialTheme.typography.bodyMedium)
            // The playlists live on their own screen: here only how many there are and a way in.
            if (lists.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.files_summary, lists.size, lists.sumOf { it.trackCount.coerceAtLeast(0) }),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = onManage) { Text(stringResource(R.string.files_manage)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { pasteOpen = true }) { Text(stringResource(R.string.paste_list)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "text/*", "text/csv", "text/comma-separated-values", "text/plain",
                            "application/json", "application/xml", "text/xml",
                            "audio/x-mpegurl", "application/vnd.apple.mpegurl", "application/octet-stream",
                        )
                    )
                }) {
                    Text(stringResource(R.string.csv_import))
                }
            }
        }
    }
}

/** A connector with no account (the charts): nothing to connect, only what it is. */
@Composable
private fun PlainSourceCard(p: MusicProvider, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(p, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(p.label(ctx), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OverflowMenu(listOf(MenuAction(stringResource(R.string.account_remove), onRemove, destructive = true)))
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(p.noteRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun displayName(ctx: android.content.Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0) ?: "playlist"
    }
    return uri.lastPathSegment ?: "playlist"
}

/** Un blocco di testo "Artista - Titolo" per riga diventa una playlist da file. */
@Composable
private fun PasteListDialog(onDismiss: () -> Unit, onImport: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_list)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.paste_list_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.paste_list_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 5, maxLines = 10, label = { Text(stringResource(R.string.paste_list_text)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onImport(name.ifBlank { "playlist" }, text) }) { Text(stringResource(R.string.import_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Streaming services, personal servers, then the rest (stats services, files), each in its own group. */
private fun serviceGroups(names: List<String>, families: Map<String, List<MusicProvider>>): List<Pair<Int, List<String>>> {
    val servers = setOf("subsonic", "jellyfin", "emby", "plex", "lyrion", "mopidy")
    val other = setOf("lastfm", "listenbrainz", "csv")
    fun kind(n: String) = families.getValue(n).first().serviceId.let { if (it in servers) 1 else if (it in other) 2 else 0 }
    return listOf(
        R.string.services_group_streaming to names.filter { kind(it) == 0 },
        R.string.services_group_servers to names.filter { kind(it) == 1 },
        R.string.services_group_other to names.filter { kind(it) == 2 },
    )
}

/** Services with no official API this version can use: id, brand colour, name. */
private val UNAVAILABLE_ON_PLAY = listOf(
    Triple("amazon", 0xFF1AB0D6, "Amazon Music"),
    Triple("soundcloud", 0xFFFF5500, "SoundCloud"),
    Triple("netease", 0xFFC20C0C, "NetEase Cloud Music"),
    Triple("anghami", 0xFF8D3DEB, "Anghami"),
    Triple("qobuz", QobuzBridgeProvider.BRAND, "Qobuz"),
    Triple("qqmusic", 0xFF31C27C, "QQ Music"),
)

/** A greyed-out tile for a service this version cannot reach: the mark and the name, nothing to tap. */
@Composable
private fun UnavailableServiceTile(serviceId: String, brandColor: Long, name: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(84.dp).heightIn(min = 96.dp).alpha(0.45f),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
            ServiceLogo(serviceId, brandColor, name, 40.dp)
            Spacer(Modifier.height(6.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, minLines = 2, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A service in the "add a service" grid: its mark, large, and its name. */
@Composable
internal fun ServiceGridTile(p: MusicProvider, name: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(84.dp).heightIn(min = 96.dp).alpha(if (enabled) 1f else 0.45f),
    ) {
        // Logo at the top and every label two lines tall: with names on one line and on two
        // ("YouTube Music", "Subsonic / Navidrome"), centring moved the logos up and down.
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
            ServiceLogo(p, 40.dp)
            Spacer(Modifier.height(6.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, minLines = 2, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
    }
}
