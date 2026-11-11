package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Dns
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.NtfyServer
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.shared.SharedProvider
import com.xlollx.songport.shared.SharedTransports
import com.xlollx.songport.sync.QrCode
import com.xlollx.songport.sync.SafFolder
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.ServerLocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What opens the shared playlists' screen: the Tools row, a card's menu, an invite link. */
object SharedUi {
    sealed interface Request {
        data object List : Request
        data class Open(val groupId: String) : Request
        /** A new group around [local], one of the user's playlists (a sync's target). */
        data class Create(val local: PlaylistRef, val name: String) : Request
        data class Join(val link: String) : Request
    }

    val request = MutableStateFlow<Request?>(null)

    /** A sync whose target is one of the user's own playlists on a service that can be written: it can become a group's. */
    fun canShare(job: SyncJob): Boolean {
        val p = Providers.byId(job.target.provider) ?: return false
        return job.target.playlistId != null && !MusicProvider.isLibrary(job.target.playlistId) && p.canWrite && p.canRemoveTracks &&
            p.serviceId != com.xlollx.songport.providers.LocalFilesProvider.serviceId && !job.twin && job.radio == null
    }
}

/** "Shared" in place of the group's side of a sync, where a service's name would be. */
@Composable
fun sharedLabel(provider: String): String? = if (provider == SharedProvider.ID) stringResource(R.string.shared_badge) else null

/** A folder picked in the system's picker, kept readable and writable across restarts. */
@Composable
private fun rememberFolderPicker(onPicked: (Uri, String) -> Unit) = LocalContext.current.let { ctx ->
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            onPicked(uri, SafFolder.displayName(ctx, uri).ifBlank { uri.lastPathSegment.orEmpty() })
        }
    }
}

/**
 * Gives back the lasting access to a group's former folder once nothing in Trackhop uses it (no
 * other group, not the backup folder): Android keeps a limited number of them per app.
 */
private fun releaseFolder(ctx: android.content.Context, ref: String) {
    if (!ref.startsWith("content://")) return
    val d = ctx.env.store.data
    if (d.groups.any { it.folder == ref } || d.settings.backupFolder == ref) return
    runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(ref), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
}

/**
 * Shared playlists: the groups this phone takes part in, creating one around a playlist, joining
 * with an invite link, and a group's page (invite, members and roles, the log, leaving).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val data by Store.get(ctx).state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var createFrom by remember { mutableStateOf<SharedUi.Request.Create?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var joinLink by rememberSaveable { mutableStateOf<String?>(null) }
    val request by SharedUi.request.collectAsState()
    LaunchedEffect(request) {
        when (val r = request) {
            is SharedUi.Request.Open -> openId = r.groupId
            is SharedUi.Request.Create -> { createFrom = r; creating = true }
            is SharedUi.Request.Join -> joinLink = r.link
            else -> {}
        }
        SharedUi.request.value = null
    }
    val group = openId?.let { id -> data.groups.firstOrNull { it.id == id } }
    ScreenBackHandler { if (group != null) openId = null else onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: stringResource(R.string.shared_title)) },
                navigationIcon = { IconButton(onClick = { if (group != null) openId = null else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (group != null) GroupPage(group, snackbar) { openId = null }
            else GroupList(data.groups, onOpen = { openId = it }, onCreate = { createFrom = null; creating = true }, onJoin = { joinLink = "" })
        }
    }
    val scope = rememberCoroutineScope()
    if (creating) CreateGroupDialog(createFrom, onDismiss = { creating = false; createFrom = null }) { g ->
        creating = false; createFrom = null; openId = g.id
        ctx.env.store.job(g.jobId)?.let { Scheduler.apply(ctx, it) }
        ctx.env.store.job(g.twinId)?.let { Scheduler.apply(ctx, it) }
        // The playlist's tracks reach the group now.
        Scheduler.runInSequence(ctx, listOf(g.twinId, g.jobId))
        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.shared_created)) }
    }
    joinLink?.let { link ->
        JoinGroupDialog(link, onDismiss = { joinLink = null }, onRekeyed = { g, said ->
            joinLink = null; openId = g.id
            scope.launch { snackbar.showSnackbar(ctx.getString(said, g.name)) }
        }) { g, others ->
            joinLink = null; openId = g.id
            ctx.env.store.job(g.jobId)?.let { Scheduler.apply(ctx, it) }
            ctx.env.store.job(g.twinId)?.let { Scheduler.apply(ctx, it) }
            Scheduler.runInSequence(ctx, listOf(g.jobId, g.twinId))
            scope.launch {
                snackbar.showSnackbar(when {
                    others -> ctx.getString(R.string.shared_joined, g.name)
                    g.transport != Invite.FOLDER -> ctx.getString(R.string.shared_joined_waiting, g.name)
                    else -> ctx.getString(R.string.shared_joined_alone)
                })
            }
        }
    }
}

@Composable
private fun GroupList(groups: List<SharedGroup>, onOpen: (String) -> Unit, onCreate: () -> Unit, onJoin: () -> Unit) {
    if (groups.isEmpty()) {
        SharedEmptyState(onCreate, onJoin)
        return
    }
    // Side by side when they fit, one under the other when not: a label never breaks inside its button.
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.GroupAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shared_create), maxLines = 1, softWrap = false) }
        OutlinedButton(onClick = onJoin, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shared_join), maxLines = 1, softWrap = false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (g in groups) GroupCard(g) { onOpen(g.id) }
        HowItWorks()
    }
}

/** No group yet: what a shared playlist is, in three lines, and the two ways in. */
@Composable
private fun SharedEmptyState(onCreate: () -> Unit, onJoin: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Groups, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.shared_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EmptyPoint(Icons.Filled.LibraryMusic, stringResource(R.string.shared_empty_copy))
            EmptyPoint(Icons.Filled.Sync, stringResource(R.string.shared_empty_meet))
            EmptyPoint(Icons.Filled.Link, stringResource(R.string.shared_empty_invite))
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.GroupAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shared_create))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shared_join))
        }
    }
}

@Composable
private fun EmptyPoint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrefIcon(icon)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** The longer explanation, folded under the groups: they already know the basics. */
@Composable
private fun HowItWorks() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                .toggleable(open, role = Role.Button) { open = it }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.shared_how), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(open) {
            Text(
                stringResource(R.string.shared_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
            )
        }
    }
}

/** When the group last synced here (either way), and whether that run failed. */
private data class GroupRuns(val last: Long, val failed: Boolean)

@Composable
private fun groupRuns(g: SharedGroup): GroupRuns {
    val data by Store.get(LocalContext.current).state.collectAsState()
    val jobs = data.jobs.filter { it.id == g.jobId || it.id == g.twinId }
    val failed = jobs.any { j -> data.reports.firstOrNull { it.id == j.lastReportId }?.ok == false }
    return GroupRuns(jobs.maxOfOrNull { it.lastRunEpoch } ?: 0L, failed)
}

/** One group in the list: its name, this member's role, how it meets, how many are in it, the last sync, a warning when that failed. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(g: SharedGroup, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val st = remember(g.id) { SharedGroups.load(ctx.env, g.id) }
    val curator = SharedGroups.role(g, st) == SharedGroups.Role.CURATOR
    val count = SharedGroups.members(g, st).count { !it.left }
    val runs = groupRuns(g)
    val dark = LocalAppDark.current
    androidx.compose.material3.Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 1.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PrefIcon(transportIcon(g.transport))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(stringResource(if (curator) R.string.shared_role_curator else R.string.shared_role_follower), if (curator) Tone.Accent else Tone.Neutral)
                    if (runs.failed) StatusPill(stringResource(R.string.pill_error), Tone.Error, Icons.Filled.Warning)
                }
                Text(
                    listOf(
                        transportLabel(g.transport),
                        pluralStringResource(R.plurals.shared_members_n, count, count),
                        if (runs.last > 0) stringResource(R.string.pill_last_run, relativeTime(runs.last)) else stringResource(R.string.last_run_never),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The service (account) a member listens on, among those connected that can hold a playlist of the group. */
private fun writableServices(ctx: android.content.Context, needsCreate: Boolean): List<MusicProvider> =
    Providers.connectors().filter {
        it.isConnected(ctx.env) && it.canWrite && it.canRemoveTracks && it.serviceId != com.xlollx.songport.providers.LocalFilesProvider.serviceId &&
            (!needsCreate || it.canCreatePlaylists)
    }

/** The service a member listens on, as a dropdown with each service's mark. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceDropdown(services: List<MusicProvider>, selected: MusicProvider?, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && services.isNotEmpty() }) {
        OutlinedTextField(
            value = selected?.label(ctx.env).orEmpty(), onValueChange = {}, readOnly = true, singleLine = true, enabled = services.isNotEmpty(),
            label = { Text(stringResource(R.string.shared_service)) },
            leadingIcon = selected?.let { p -> { ServiceLogo(p, 24.dp) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in services) DropdownMenuItem(
                text = { Text(p.label(ctx.env)) },
                leadingIcon = { ServiceLogo(p, 24.dp) },
                onClick = { open = false; onSelect(p.id) },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
        }
    }
}

/**
 * The playlist a new group starts from, as the sync editor's picker: the user's own playlists on
 * the chosen service and, first, a new one named after the group ([canCreate]). [lists] null: loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDropdown(lists: List<Playlist>?, selected: Playlist?, makeNew: Boolean, canCreate: Boolean, onNew: () -> Unit, onSelect: (Playlist) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && lists != null }) {
        OutlinedTextField(
            value = when {
                makeNew -> stringResource(R.string.shared_new_playlist)
                else -> selected?.name.orEmpty()
            },
            onValueChange = {}, readOnly = true, singleLine = true, enabled = lists != null,
            label = { Text(stringResource(R.string.shared_playlist)) },
            placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
            leadingIcon = { Icon(if (makeNew) Icons.AutoMirrored.Filled.PlaylistAdd else Icons.AutoMirrored.Filled.QueueMusic, null) },
            trailingIcon = {
                if (lists == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ExposedDropdownMenuDefaults.TrailingIcon(open)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canCreate) DropdownMenuItem(
                text = { Text(stringResource(R.string.shared_new_playlist)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { open = false; onNew() },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
            for (p in lists.orEmpty()) DropdownMenuItem(
                text = {
                    Column {
                        Text(p.name)
                        if (p.trackCount >= 0) Text(stringResource(R.string.tracks_count, p.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                onClick = { open = false; onSelect(p) },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
        }
    }
}

/** Under a form whose button is still off: what it waits for, by the fields' names. */
@Composable
private fun MissingNote(missing: List<String>) {
    if (missing.isEmpty()) return
    Text(
        stringResource(R.string.shared_missing, missing.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A new group: its name, the user's playlist it starts from (or a new one), their name, how it meets. */
@Composable
private fun CreateGroupDialog(from: SharedUi.Request.Create?, onDismiss: () -> Unit, onDone: (SharedGroup) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { writableServices(ctx, needsCreate = false) }
    var name by rememberSaveable { mutableStateOf(from?.name.orEmpty()) }
    var myName by rememberSaveable { mutableStateOf("") }
    var serviceId by rememberSaveable { mutableStateOf(from?.local?.provider ?: services.firstOrNull()?.id) }
    var playlist by remember { mutableStateOf(from?.local?.takeIf { it.playlistId != null }?.let { Playlist(it.playlistId!!, it.playlistName) }) }
    // A new playlist on the service, named after the group, made when the group is.
    var makeNew by rememberSaveable { mutableStateOf(false) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var transport by rememberSaveable { mutableStateOf(Invite.FOLDER) }
    var server by rememberSaveable { mutableStateOf(DEFAULT_NTFY_SERVER) }
    val signIn = remember { NtfySignIn() }
    // The user's own Trackhop server, when "Coordinate with my server" is set: a third way to meet.
    val myServer = remember { ServerLocks.config(ctx.env)?.address }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberFolderPicker { uri, n -> folder = uri.toString(); folderName = n }
    val service = serviceId?.let { Providers.byId(it) }
    val canCreate = service?.canCreatePlaylists == true
    LaunchedEffect(serviceId) {
        lists = null
        val p = service ?: return@LaunchedEffect
        lists = withContext(Dispatchers.IO) { runCatching { p.playlists(ctx.env).filter { it.ownedByMe } }.getOrDefault(emptyList()) }
    }
    val missing = listOfNotNull(
        stringResource(R.string.shared_name).takeIf { name.isBlank() },
        stringResource(R.string.shared_your_name).takeIf { myName.isBlank() },
        stringResource(R.string.shared_service).takeIf { service == null },
        stringResource(R.string.shared_playlist).takeIf { service != null && playlist == null && !makeNew },
        when (transport) {
            Invite.NTFY -> when {
                !NtfyServer.valid(server) -> stringResource(R.string.shared_ntfy_server)
                !signIn.ready -> stringResource(R.string.shared_ntfy_signin_change)
                else -> null
            }
            Invite.SERVER -> null
            else -> stringResource(R.string.shared_folder).takeIf { folder == null }
        },
    )
    FitDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (services.isEmpty()) Text(stringResource(R.string.shared_no_services), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it.take(Invite.MAX_NAME) }, label = { Text(stringResource(R.string.shared_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(myName, { myName = it.take(40) }, label = { Text(stringResource(R.string.shared_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ServiceDropdown(services, service) { if (it != serviceId) { serviceId = it; playlist = null; makeNew = false } }
                if (service != null) PlaylistDropdown(lists, playlist, makeNew, canCreate, onNew = { makeNew = true; playlist = null }) {
                    makeNew = false; playlist = it; if (name.isBlank()) name = it.name
                }
                TransportChoice(transport, { transport = it }, server, { server = it }, myServer?.let { NtfyServer.hostOf(it) }, signIn) {
                    FolderPicker(folderName) { pick.launch(null) }
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!busy) MissingNote(missing)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && missing.isEmpty() && (transport != Invite.SERVER || myServer != null),
                onClick = {
                    val p = service ?: return@TextButton
                    val pl = playlist
                    if (pl == null && !makeNew) return@TextButton
                    busy = true; error = null
                    scope.launch {
                        val made = withContext(Dispatchers.IO) {
                            runCatching {
                                val local = if (pl != null) PlaylistRef(p.id, pl.id, pl.name)
                                else p.createPlaylist(ctx.env, name, MusicProvider.description(ctx.getString(R.string.app_name))).let { PlaylistRef(p.id, it.id, it.name) }
                                when (transport) {
                                    Invite.NTFY -> SharedGroups.create(ctx.env, name, server, NtfyServer.hostOf(server), myName, p.displayName, local, Invite.NTFY, signIn.auth)
                                    Invite.SERVER -> SharedGroups.create(ctx.env, name, myServer!!, NtfyServer.hostOf(myServer), myName, p.displayName, local, Invite.SERVER)
                                    else -> SharedGroups.create(ctx.env, name, folder!!, folderName, myName, p.displayName, local)
                                }
                            }
                        }
                        busy = false
                        made.onSuccess(onDone).onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
                    }
                },
            ) { Text(stringResource(R.string.shared_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Joining: the invite link (pasted, or opened from a message). Until it reads as an invite nothing
 * else shows; then what it says (the group, how it meets, the code) and only what that way needs:
 * the member's name and service (a new playlist there), the shared folder, a sign-in.
 */
@Composable
private fun JoinGroupDialog(initial: String, onDismiss: () -> Unit, onRekeyed: (SharedGroup, Int) -> Unit, onDone: (SharedGroup, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val services = remember { writableServices(ctx, needsCreate = true) }
    // A new invite opened while the form shows replaces the one in it.
    var link by rememberSaveable(initial) { mutableStateOf(initial) }
    var myName by rememberSaveable { mutableStateOf("") }
    var serviceId by rememberSaveable { mutableStateOf(services.firstOrNull()?.id) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberFolderPicker { uri, n -> folder = uri.toString(); folderName = n }
    val invite = remember(link) { Invite.parse(link) }
    // A protected ntfy server: this member's own sign-in (the invite only says one is needed).
    val signIn = remember(invite?.signIn) { NtfySignIn(on = invite?.signIn == true) }
    // A new invite of a group this device is in (its creator replaced the server key): only the key changes.
    val rekey = remember(link) { invite?.let { SharedGroups.rekeyed(ctx.env, it) } }
    // A new invite of a group this device is in, on another ntfy server (it moved): only the server changes.
    val move = remember(link) { invite?.let { SharedGroups.moved(ctx.env, it) } }
    val service = serviceId?.let { Providers.byId(it) }
    val asksSignIn = invite?.transport == Invite.NTFY && invite.signIn && rekey == null
    val missing = when {
        invite == null || rekey != null -> emptyList()
        move != null -> listOfNotNull(stringResource(R.string.shared_ntfy_signin_change).takeIf { !signIn.ready })
        else -> listOfNotNull(
            stringResource(R.string.shared_your_name).takeIf { myName.isBlank() },
            stringResource(R.string.shared_service).takeIf { service == null },
            stringResource(R.string.shared_folder).takeIf { invite.transport == Invite.FOLDER && folder == null },
            stringResource(R.string.shared_ntfy_signin_change).takeIf { invite.transport == Invite.NTFY && !signIn.ready },
        )
    }
    FitDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_join)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val bad = link.isNotBlank() && invite == null
                OutlinedTextField(
                    link, { link = it.trim() }, label = { Text(stringResource(R.string.shared_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = bad,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { link = it } }) {
                            Icon(Icons.Filled.ContentPaste, stringResource(R.string.lastfm_key_paste))
                        }
                    },
                    supportingText = when {
                        bad -> { { Text(stringResource(R.string.shared_link_invalid)) } }
                        link.isBlank() -> { { Text(stringResource(R.string.shared_link_hint)) } }
                        else -> null
                    },
                )
                if (invite != null) {
                    InviteSummary(invite)
                    if (rekey != null) Text(stringResource(R.string.shared_key_new_invite, rekey.name), style = MaterialTheme.typography.bodyMedium)
                    else if (move != null) Text(stringResource(R.string.shared_server_new_invite, move.name, NtfyServer.hostOf(invite.server)), style = MaterialTheme.typography.bodyMedium)
                    else {
                        if (services.isEmpty()) Text(stringResource(R.string.shared_no_services), color = MaterialTheme.colorScheme.error)
                        OutlinedTextField(myName, { myName = it.take(40) }, label = { Text(stringResource(R.string.shared_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        ServiceDropdown(services, service) { serviceId = it }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.shared_new_playlist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (invite.transport == Invite.FOLDER) FolderPicker(folderName) { pick.launch(null) }
                    }
                    if (asksSignIn) NtfySignInFields(signIn)
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!busy) MissingNote(missing)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && invite != null && missing.isEmpty(),
                onClick = {
                    val inv = invite ?: return@TextButton
                    rekey?.let { g ->
                        busy = true; error = null
                        scope.launch {
                            val taken = withContext(Dispatchers.IO) { runCatching { SharedGroups.takeKey(ctx.env, g, inv) } }
                            busy = false
                            taken.onSuccess { onRekeyed(it, R.string.shared_key_updated) }.onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
                        }
                        return@TextButton
                    }
                    move?.let { g ->
                        busy = true; error = null
                        scope.launch {
                            val taken = withContext(Dispatchers.IO) { runCatching { SharedGroups.takeServer(ctx.env, g, inv, signIn.auth) } }
                            busy = false
                            taken.onSuccess { onRekeyed(it, R.string.shared_server_updated) }.onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
                        }
                        return@TextButton
                    }
                    val p = service ?: return@TextButton
                    busy = true; error = null
                    scope.launch {
                        val joined = withContext(Dispatchers.IO) {
                            runCatching {
                                if (SharedGroups.group(ctx.env, inv.groupId) != null) throw com.xlollx.songport.model.ProviderException(ctx.getString(R.string.shared_error_already, inv.name))
                                // The member's own copy: a new playlist on their service, filled by the first sync.
                                val made = p.createPlaylist(ctx.env, inv.name, MusicProvider.description(ctx.getString(R.string.app_name)))
                                SharedGroups.join(ctx.env, inv, folder.orEmpty(), folderName, myName, p.displayName, PlaylistRef(p.id, made.id, made.name), signIn.auth?.takeIf { inv.transport == Invite.NTFY })
                            }
                        }
                        busy = false
                        joined.onSuccess { (g, others) -> onDone(g, others) }.onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
                    }
                },
            ) { Text(stringResource(R.string.shared_join)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A group's page: sync, invite, members and roles, the follower's choice, the log, the folder, leaving. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupPage(group: SharedGroup, snackbar: SnackbarHostState, onLeft: () -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var state by remember(group.id) { mutableStateOf(SharedGroups.load(env, group.id)) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(group.id, group.folder, version) {
        val r = withContext(Dispatchers.IO) { runCatching { SharedGroups.refresh(env, group) } }
        r.onSuccess { state = it; problem = null }.onFailure { problem = it.message }
    }
    fun act(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }.onFailure { snackbar.showSnackbar(it.message ?: ctx.getString(R.string.error_generic)) }
            version++
        }
    }
    val members = SharedGroups.members(group, state)
    val names = members.associate { it.id to it.name.ifBlank { ctx.getString(R.string.shared_someone) } }
    val curator = SharedGroups.role(group, state) == SharedGroups.Role.CURATOR
    val changeFolder = rememberFolderPicker { uri, n ->
        val before = group.folder
        env.store.update { d -> d.copy(groups = d.groups.map { if (it.id == group.id) it.copy(folder = uri.toString(), folderName = n) else it }) }
        releaseFolder(ctx, before)
    }

    problem?.let {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }

    // Who this member is in the group, how it meets, how many are in it and when it last synced.
    val runs = groupRuns(group)
    val count = members.count { !it.left }
    val dark = LocalAppDark.current
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 1.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            PrefIcon(transportIcon(group.transport))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOf(transportLabel(group.transport), group.folderName).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(stringResource(if (curator) R.string.shared_role_curator else R.string.shared_role_follower), if (curator) Tone.Accent else Tone.Neutral)
                    StatusPill(pluralStringResource(R.plurals.shared_members_n, count, count), Tone.Neutral, Icons.Filled.Groups)
                    if (runs.failed) StatusPill(stringResource(R.string.pill_error), Tone.Error, Icons.Filled.Warning)
                }
                Text(
                    if (runs.last > 0) stringResource(R.string.pill_last_run, relativeTime(runs.last)) else stringResource(R.string.last_run_never),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = {
                    Scheduler.runInSequence(ctx, listOf(group.twinId, group.jobId))
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.running)) }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shared_sync_now)) }
            }
        }
    }

    // The invite: a QR code for the phone's camera, the code to compare, and the same link to send.
    val secret = group.transport != Invite.FOLDER
    val link = remember(group, version) { SharedTransports.invite(env, group).link() }
    PrefSection(stringResource(R.string.shared_invite), accent = JobColor.PINK, description = if (secret) null else stringResource(R.string.shared_invite_desc)) {
        PrefBlock(indent = false) {
            if (secret) InviteSecretNote()
            val symbol = remember(link) { runCatching { QrCode.encode(link) }.getOrNull() }
            val qrSaid = stringResource(R.string.shared_invite)
            if (symbol != null) Box(
                Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp).size(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)
                    .semantics { contentDescription = qrSaid },
            ) {
                Canvas(Modifier.size(176.dp)) {
                    val m = size.width / symbol.size
                    for (y in 0 until symbol.size) for (x in 0 until symbol.size) if (symbol[x, y]) drawRect(Color.Black, Offset(x * m, y * m), Size(m + 0.5f, m + 0.5f))
                }
            }
            GroupCode(group.invite().code, Modifier.align(Alignment.CenterHorizontally))
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shared_share_link))
            }
            SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }

    PrefSection(stringResource(R.string.shared_members), accent = JobColor.BLUE, description = stringResource(R.string.shared_roles_desc)) {
        for (m in members) {
            val role = when {
                m.left -> stringResource(R.string.shared_left)
                m.curator -> stringResource(R.string.shared_role_curator)
                else -> stringResource(R.string.shared_role_follower)
            }
            val who = listOfNotNull(
                if (m.me) stringResource(R.string.shared_you) else null,
                if (m.creator) stringResource(R.string.shared_creator) else null,
                m.service.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            PrefRow(
                if (m.curator && !m.left) Icons.Filled.Star else Icons.Filled.Person, names[m.id].orEmpty(), listOf(role, who).filter { it.isNotBlank() }.joinToString(" · "), chevron = false,
                trailing = if (group.createdHere && !m.left && (!m.me || members.count { it.curator } > 1)) {
                    { TextButton(onClick = { act { SharedGroups.setCurator(env, group, m.id, !m.curator) } }) {
                        Text(stringResource(if (m.curator) R.string.shared_unmake_curator else R.string.shared_make_curator))
                    } }
                } else null,
            )
        }
    }

    if (!curator) PrefSection(stringResource(R.string.shared_follow_mode), accent = JobColor.TEAL) {
        PrefRow(null, stringResource(R.string.shared_mode_exact), stringResource(R.string.shared_mode_exact_desc), chevron = false,
            trailing = { androidx.compose.material3.RadioButton(selected = !group.keepMine, onClick = { act { SharedGroups.setKeepMine(env, group, false) } }) }) {
            act { SharedGroups.setKeepMine(env, group, false) }
        }
        PrefRow(null, stringResource(R.string.shared_mode_keep), stringResource(R.string.shared_mode_keep_desc), chevron = false,
            trailing = { androidx.compose.material3.RadioButton(selected = group.keepMine, onClick = { act { SharedGroups.setKeepMine(env, group, true) } }) }) {
            act { SharedGroups.setKeepMine(env, group, true) }
        }
    }

    PrefSection(stringResource(R.string.shared_log), accent = JobColor.ORANGE) {
        val history = remember(state) { SharedGroups.history(state).take(MAX_LOG) }
        if (history.isEmpty()) PrefBlock { Text(stringResource(R.string.shared_log_empty), style = MaterialTheme.typography.bodyMedium) }
        for (e in history) {
            val who = names[e.by] ?: stringResource(R.string.shared_someone)
            PrefBlock {
                Text(stringResource(if (e.adds) R.string.shared_log_added else R.string.shared_log_removed, who, e.track().toString()), style = MaterialTheme.typography.bodyMedium)
                Text(formatDate(e.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    var confirmLeave by remember { mutableStateOf(false) }
    var confirmKey by remember { mutableStateOf(false) }
    var signInOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    if (moveOpen) MoveServerDialog(group, onDismiss = { moveOpen = false }) { moveOpen = false; version++ }
    if (signInOpen) NtfySignInDialog(group, onDismiss = { signInOpen = false }) { signInOpen = false; version++ }
    val via = when (group.transport) {
        Invite.NTFY -> R.string.shared_via_ntfy
        Invite.SERVER -> R.string.shared_via_server
        else -> R.string.shared_folder
    }
    PrefSection(stringResource(via), accent = JobColor.GREEN) {
        when (group.transport) {
            Invite.NTFY -> {
                PrefRow(Icons.Filled.Lock, stringResource(R.string.shared_ntfy_server), group.folderName, chevron = false)
                // This member's own sign-in to a protected server: changed when their token or password changes.
                PrefRow(
                    Icons.Filled.Key, stringResource(R.string.shared_ntfy_signin_change),
                    stringResource(if (remember(version) { SharedTransports.ntfyAuth(env, group) } != null) R.string.shared_ntfy_signin_on else R.string.shared_ntfy_signin_off),
                ) { signInOpen = true }
                // A curator moves the group to another ntfy server; the others follow on their next sync.
                if (curator) PrefRow(Icons.Filled.Dns, stringResource(R.string.shared_change_server), null) { moveOpen = true }
            }
            Invite.SERVER -> {
                PrefRow(Icons.Filled.Dns, stringResource(R.string.shared_server_label), group.folderName, chevron = false)
                // The creator, with that server's token on this device: a new key shuts out whoever holds the old invite.
                if (group.createdHere && ServerLocks.config(env)?.address == ServerLocks.normalize(group.folder)) PrefRow(Icons.Filled.Key, stringResource(R.string.shared_new_key), null, chevron = false) { confirmKey = true }
            }
            else -> PrefRow(Icons.Filled.Folder, stringResource(R.string.shared_change_folder), group.folderName) { changeFolder.launch(null) }
        }
        PrefRow(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.shared_leave), null, danger = true, chevron = false) { confirmLeave = true }
    }
    if (confirmKey) FitDialog(
        onDismissRequest = { confirmKey = false },
        title = { Text(stringResource(R.string.shared_new_key)) },
        text = { Text(stringResource(R.string.shared_new_key_confirm)) },
        confirmButton = {
            TextButton(onClick = { confirmKey = false; act { SharedGroups.newKey(env, group) } }) {
                Text(stringResource(R.string.shared_new_key), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmKey = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmLeave) FitDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.shared_leave)) },
        text = { Text(stringResource(R.string.shared_leave_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                confirmLeave = false
                scope.launch {
                    val ids = withContext(Dispatchers.IO) { SharedGroups.leave(env, group) }
                    ids.forEach { Scheduler.cancel(ctx, it) }
                    releaseFolder(ctx, group.folder)
                    onLeft()
                }
            }) { Text(stringResource(R.string.shared_leave), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** This member's sign-in to the group's ntfy server, changed: kept only when the server takes it. */
@Composable
private fun NtfySignInDialog(group: SharedGroup, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val signIn = remember { NtfySignIn(on = SharedTransports.ntfyAuth(ctx.env, group) != null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FitDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_ntfy_signin_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NtfySignInFields(signIn)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && signIn.ready, onClick = {
                busy = true; error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { SharedGroups.setNtfyAuth(ctx.env, group, signIn.auth) } }
                    busy = false
                    r.onSuccess { onDone() }.onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A curator moves the group's encrypted channel to another ntfy server (see [SharedGroups.moveNtfy]). */
@Composable
private fun MoveServerDialog(group: SharedGroup, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    val signIn = remember { NtfySignIn() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FitDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_change_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shared_change_server_desc), style = MaterialTheme.typography.bodyMedium)
                val bad = server.isNotBlank() && !NtfyServer.valid(server)
                OutlinedTextField(
                    server, { server = it.trim().take(200) }, label = { Text(stringResource(R.string.shared_ntfy_server)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), isError = bad,
                    supportingText = if (bad) { { Text(stringResource(R.string.shared_ntfy_server_invalid)) } } else null,
                )
                NtfySignInFields(signIn)
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && NtfyServer.valid(server) && signIn.ready, onClick = {
                busy = true; error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { SharedGroups.moveNtfy(ctx.env, group, server, signIn.auth) } }
                    busy = false
                    r.onSuccess { onDone() }.onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
                }
            }) { Text(stringResource(R.string.shared_change_server)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** How much of the log the page lists: the most recent actions. */
private const val MAX_LOG = 200
