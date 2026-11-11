package com.xlollx.songport.ui

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
        JoinGroupDialog(link, onDismiss = { joinLink = null }, onRekeyed = { g ->
            joinLink = null; openId = g.id
            scope.launch { snackbar.showSnackbar(ctx.getString(R.string.shared_key_updated, g.name)) }
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
    val ctx = LocalContext.current
    Text(stringResource(R.string.shared_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onCreate) { Icon(Icons.Filled.GroupAdd, null, Modifier.size(18.dp)); Text("  " + stringResource(R.string.shared_create)) }
        OutlinedButton(onClick = onJoin) { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Text("  " + stringResource(R.string.shared_join)) }
    }
    if (groups.isNotEmpty()) PrefSection(stringResource(R.string.shared_title), accent = JobColor.PINK) {
        for (g in groups) {
            val st = remember(g.id) { SharedGroups.load(ctx.env, g.id) }
            val curator = SharedGroups.role(g, st) == SharedGroups.Role.CURATOR
            val count = SharedGroups.members(g, st).count { !it.left }
            PrefRow(
                Icons.Filled.Groups, g.name,
                stringResource(if (curator) R.string.shared_role_curator else R.string.shared_role_follower) + " · " +
                    pluralStringResource(R.plurals.shared_members_n, count, count) + " · " + g.folderName,
            ) { onOpen(g.id) }
        }
    }
}

/** The service (account) a member listens on, among those connected that can hold a playlist of the group. */
private fun writableServices(ctx: android.content.Context, needsCreate: Boolean): List<MusicProvider> =
    Providers.connectors().filter {
        it.isConnected(ctx.env) && it.canWrite && it.canRemoveTracks && it.serviceId != com.xlollx.songport.providers.LocalFilesProvider.serviceId &&
            (!needsCreate || it.canCreatePlaylists)
    }

@Composable
private fun FolderField(name: String, onPick: () -> Unit) {
    Column {
        Text(stringResource(R.string.shared_folder), style = MaterialTheme.typography.labelLarge)
        Text(name.ifBlank { stringResource(R.string.shared_folder_none) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onPick) { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)); Text("  " + stringResource(R.string.shared_folder_pick)) }
    }
}

@Composable
private fun ChoiceField(label: String, value: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onClick, enabled = enabled) { Text(value ?: "—") }
    }
}

/** A new group: its name, the user's playlist it starts from, their name, the shared folder. */
@Composable
private fun CreateGroupDialog(from: SharedUi.Request.Create?, onDismiss: () -> Unit, onDone: (SharedGroup) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { writableServices(ctx, needsCreate = false) }
    var name by rememberSaveable { mutableStateOf(from?.name.orEmpty()) }
    var myName by rememberSaveable { mutableStateOf("") }
    var serviceId by rememberSaveable { mutableStateOf(from?.local?.provider ?: services.firstOrNull()?.id) }
    var playlist by remember { mutableStateOf(from?.local?.takeIf { it.playlistId != null }?.let { Playlist(it.playlistId!!, it.playlistName) }) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(serviceId) {
        lists = null
        val p = service ?: return@LaunchedEffect
        lists = withContext(Dispatchers.IO) { runCatching { p.playlists(ctx.env).filter { it.ownedByMe } }.getOrDefault(emptyList()) }
    }
    when (choosing) {
        "service" -> ChoiceDialog(stringResource(R.string.shared_service), services.map { it.id to it.label(ctx.env) }, serviceId, onDismiss = { choosing = null }) {
            choosing = null; if (it != serviceId) { serviceId = it; playlist = null }
        }
        "playlist" -> ChoiceDialog(stringResource(R.string.shared_playlist), lists.orEmpty().map { it to it.name }, playlist, onDismiss = { choosing = null }) {
            choosing = null; playlist = it; if (name.isBlank()) name = it.name
        }
    }
    FitDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (services.isEmpty()) Text(stringResource(R.string.shared_no_services))
                OutlinedTextField(name, { name = it.take(Invite.MAX_NAME) }, label = { Text(stringResource(R.string.shared_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(myName, { myName = it.take(40) }, label = { Text(stringResource(R.string.shared_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceField(stringResource(R.string.shared_service), service?.label(ctx.env), enabled = services.isNotEmpty()) { choosing = "service" }
                ChoiceField(stringResource(R.string.shared_playlist), playlist?.name, enabled = lists != null) { choosing = "playlist" }
                TransportChoice(transport, { transport = it }, server, { server = it }, myServer?.let { NtfyServer.hostOf(it) }, signIn)
                if (transport == Invite.FOLDER) FolderField(folderName) { pick.launch(null) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && myName.isNotBlank() && service != null && playlist != null &&
                    when (transport) {
                        Invite.NTFY -> NtfyServer.valid(server) && signIn.ready
                        Invite.SERVER -> myServer != null
                        else -> folder != null
                    },
                onClick = {
                    val p = service ?: return@TextButton
                    val pl = playlist ?: return@TextButton
                    busy = true; error = null
                    scope.launch {
                        val made = withContext(Dispatchers.IO) {
                            runCatching {
                                val local = PlaylistRef(p.id, pl.id, pl.name)
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

/** Joining: the invite link, the service the member listens on (a new playlist there), their name, the shared folder. */
@Composable
private fun JoinGroupDialog(initial: String, onDismiss: () -> Unit, onRekeyed: (SharedGroup) -> Unit, onDone: (SharedGroup, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { writableServices(ctx, needsCreate = true) }
    // A new invite opened while the form shows replaces the one in it.
    var link by rememberSaveable(initial) { mutableStateOf(initial) }
    var myName by rememberSaveable { mutableStateOf("") }
    var serviceId by rememberSaveable { mutableStateOf(services.firstOrNull()?.id) }
    var choosing by remember { mutableStateOf(false) }
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
    val service = serviceId?.let { Providers.byId(it) }
    if (choosing) ChoiceDialog(stringResource(R.string.shared_service), services.map { it.id to it.label(ctx.env) }, serviceId, onDismiss = { choosing = false }) {
        choosing = false; serviceId = it
    }
    FitDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(invite?.name ?: stringResource(R.string.shared_join)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (services.isEmpty()) Text(stringResource(R.string.shared_no_services))
                OutlinedTextField(
                    link, { link = it.trim() }, label = { Text(stringResource(R.string.shared_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = link.isNotBlank() && invite == null,
                    supportingText = if (link.isNotBlank() && invite == null) { { Text(stringResource(R.string.shared_link_invalid)) } } else null,
                )
                // A link changed on its way would make its maker the group's creator here: the code says whose group it is.
                if (invite != null) Column {
                    Text(stringResource(R.string.shared_code, invite.code), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.shared_code_check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rekey != null) Text(stringResource(R.string.shared_key_new_invite, rekey.name), style = MaterialTheme.typography.bodyMedium)
                else {
                    OutlinedTextField(myName, { myName = it.take(40) }, label = { Text(stringResource(R.string.shared_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ChoiceField(stringResource(R.string.shared_service), service?.label(ctx.env), enabled = services.isNotEmpty()) { choosing = true }
                    Text(stringResource(R.string.shared_new_playlist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (invite?.transport) {
                    Invite.NTFY -> {
                        InviteSecretNote(stringResource(R.string.shared_join_ntfy, NtfyServer.hostOf(invite.server)))
                        if (rekey == null) NtfySignInFields(signIn)
                    }
                    Invite.SERVER -> InviteSecretNote(stringResource(R.string.shared_join_server, NtfyServer.hostOf(invite.server)))
                    else -> FolderField(folderName.ifBlank { invite?.folderName?.let { "($it)" }.orEmpty() }) { pick.launch(null) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && invite != null && (rekey != null || myName.isNotBlank() && service != null && (invite.transport != Invite.FOLDER || folder != null) && (invite.transport != Invite.NTFY || signIn.ready)),
                onClick = {
                    val inv = invite ?: return@TextButton
                    rekey?.let { g ->
                        busy = true; error = null
                        scope.launch {
                            val taken = withContext(Dispatchers.IO) { runCatching { SharedGroups.takeKey(ctx.env, g, inv) } }
                            busy = false
                            taken.onSuccess(onRekeyed).onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
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

    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    Text(
        stringResource(if (curator) R.string.shared_role_curator else R.string.shared_role_follower) + " · " + group.folderName,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = {
        Scheduler.runInSequence(ctx, listOf(group.twinId, group.jobId))
        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.running)) }
    }) { Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Text("  " + stringResource(R.string.shared_sync_now)) }

    // The invite: a QR code for the phone's camera, and the same link to send.
    val secret = group.transport != Invite.FOLDER
    val link = remember(group, version) { SharedTransports.invite(env, group).link() }
    PrefSection(stringResource(R.string.shared_invite), accent = JobColor.PINK, description = if (secret) null else stringResource(R.string.shared_invite_desc)) {
        PrefBlock {
            if (secret) InviteSecretNote()
            val symbol = remember(link) { runCatching { QrCode.encode(link) }.getOrNull() }
            if (symbol != null) Box(Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
                Canvas(Modifier.size(176.dp)) {
                    val m = size.width / symbol.size
                    for (y in 0 until symbol.size) for (x in 0 until symbol.size) if (symbol[x, y]) drawRect(Color.Black, Offset(x * m, y * m), Size(m + 0.5f, m + 0.5f))
                }
            }
            SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.shared_code, group.invite().code), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
            }) { Text(stringResource(R.string.shared_share_link)) }
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
                null, names[m.id].orEmpty(), listOf(role, who).filter { it.isNotBlank() }.joinToString(" · "), chevron = false,
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

/** How much of the log the page lists: the most recent actions. */
private const val MAX_LOG = 200
