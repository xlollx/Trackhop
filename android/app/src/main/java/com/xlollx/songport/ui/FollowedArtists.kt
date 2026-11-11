package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.ArtistAlerts
import com.xlollx.songport.sync.ArtistSuggest
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** [name] followed or no longer followed, and the background look in line with the list. */
internal fun toggleFollow(ctx: Context, name: String) {
    val store = Store.get(ctx)
    store.updateSettings { it.copy(followedArtists = ArtistAlerts.toggle(it.followedArtists, name, it.artistAlertKinds)) }
    Scheduler.applyArtistAlerts(ctx)
}

/** The bell beside an artist's name in Discover: follows the artist for alerts, or stops. */
@Composable
internal fun FollowBell(name: String) {
    val ctx = LocalContext.current
    val followed = Store.get(ctx).state.collectAsState().value.settings.followedArtists
    val on = ArtistAlerts.isFollowed(followed, name)
    IconToggleButton(checked = on, onCheckedChange = { toggleFollow(ctx, name) }) {
        Icon(
            if (on) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationAdd,
            stringResource(if (on) R.string.alerts_unfollow else R.string.alerts_follow, name),
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An artist's name as a card's title, with its bell. */
@Composable
internal fun ArtistTitle(name: String, small: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name, style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            fontWeight = if (small) FontWeight.Medium else FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        FollowBell(name)
    }
}

/** The search field of Discover › Followed artists, for the UI tests. */
const val FOLLOWED_SEARCH_TAG = "followed_search"

private val KIND_RES = mapOf(
    ArtistAlerts.Kind.RELEASE to R.string.alerts_kind_releases, ArtistAlerts.Kind.CONCERT to R.string.alerts_kind_concerts,
    ArtistAlerts.Kind.MERCH to R.string.alerts_kind_merch, ArtistAlerts.Kind.RECORD to R.string.alerts_kind_records,
)
private val KIND_SHORT = mapOf(
    ArtistAlerts.Kind.RELEASE to R.string.alerts_chip_releases, ArtistAlerts.Kind.CONCERT to R.string.alerts_chip_concerts,
    ArtistAlerts.Kind.MERCH to R.string.alerts_chip_merch, ArtistAlerts.Kind.RECORD to R.string.alerts_chip_records,
)
private val KIND_ICON: Map<ArtistAlerts.Kind, ImageVector> = mapOf(
    ArtistAlerts.Kind.RELEASE to Icons.Filled.MusicNote, ArtistAlerts.Kind.CONCERT to Icons.Filled.ConfirmationNumber,
    ArtistAlerts.Kind.MERCH to Icons.Filled.Checkroom, ArtistAlerts.Kind.RECORD to Icons.Filled.Album,
)

/**
 * What to be told about, one chip per kind: filled with a check mark when on, outlined with the
 * kind's own icon when off, so the two never look alike. Read aloud with the kind's full name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(kinds: ArtistAlerts.Kinds, enabled: Boolean = true, onChange: (ArtistAlerts.Kinds) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ArtistAlerts.Kind.entries.forEach { k ->
            val on = kinds[k]
            val full = stringResource(KIND_RES.getValue(k))
            FilterChip(
                selected = on, enabled = enabled, onClick = { onChange(kinds.with(k, !on)) },
                label = { Text(stringResource(KIND_SHORT.getValue(k)), maxLines = 1) },
                leadingIcon = { Icon(if (on) Icons.Filled.Check else KIND_ICON.getValue(k), null, Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.semantics { contentDescription = full },
            )
        }
    }
}

/**
 * An artist's round picture: Deezer's, where Discover shows pictures ([discoverImagesOn]); until it
 * arrives, and when there is none, the name's first letter.
 */
@Composable
internal fun ArtistAvatar(name: String, size: Dp) {
    val ctx = LocalContext.current
    var bmp by remember(name) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(name) {
        if (discoverImagesOn(ctx)) bmp = DeezerCatalog.artistPicture(name)?.let { Cards.bitmap(it, 160) }
    }
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** A suggestion: picture, the name as it is written, what tells it apart; a tap follows it. */
@Composable
private fun SuggestionRow(sg: ArtistSuggest.Suggestion, onFollow: () -> Unit) {
    val sub = when {
        sg.followed -> stringResource(R.string.alerts_already)
        sg.typed -> stringResource(R.string.alerts_as_typed)
        sg.detail.isNotBlank() -> sg.detail
        sg.known -> stringResource(R.string.alerts_from_playlists)
        else -> ""
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = !sg.followed, role = Role.Button, onClickLabel = stringResource(R.string.alerts_add), onClick = onFollow)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sg.typed) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        else ArtistAvatar(sg.name, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(sg.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (sg.followed) Icons.Filled.Check else Icons.Filled.Add, null,
            tint = if (sg.followed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

/** A followed artist: picture, name, the switch for its alerts as a whole, the menu, the kinds, and its last news. */
@Composable
private fun FollowedCard(
    f: ArtistAlerts.Followed, alertsOn: Boolean, news: List<ArtistAlerts.News>,
    onMuted: (Boolean) -> Unit, onKinds: (ArtistAlerts.Kinds) -> Unit, onUnfollow: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 6.dp, bottom = 10.dp)) {
            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtistAvatar(f.name, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).alpha(if (f.muted) 0.6f else 1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (f.muted) Text(stringResource(R.string.alerts_muted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val alertsFor = stringResource(R.string.alerts_for, f.name)
                Switch(checked = !f.muted, onCheckedChange = { onMuted(!it) }, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = alertsFor })
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.alerts_unfollow, f.name)) },
                            leadingIcon = { Icon(Icons.Filled.PersonRemove, null) },
                            onClick = { menu = false; onUnfollow() },
                        )
                    }
                }
            }
            Box(Modifier.padding(horizontal = 14.dp)) { KindChips(f.kinds, enabled = alertsOn && !f.muted, onChange = onKinds) }
            // The last news found, each to its page.
            news.forEach { n ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = n.url.isNotBlank(), role = Role.Button) { openUrl(ctx, n.url) }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(KIND_ICON.getValue(n.kind), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(ArtistAlerts.phrase(ctx.env, n).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A line of the page that asks for something: what is wrong, and the button that puts it right. */
@Composable
private fun NoticeCard(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Discover › Followed artists, top to bottom: what stops the alerts (notifications blocked, alerts
 * off); the search that suggests names while typing ([ArtistSuggest]: the artists of the user's
 * playlists first, then MusicBrainz); the artists followed, A to Z, each with a switch for its
 * alerts as a whole, a chip per kind and its last news; with nobody followed, what the page is for.
 * How often the look runs and what the next follows start with are in a sheet ([settingsOpen],
 * the gear in the page's bar).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowedArtistsPage(snackbar: SnackbarHostState, settingsOpen: Boolean, onSettingsClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val s = store.state.collectAsState().value.settings
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    fun update(f: (com.xlollx.songport.data.Settings) -> com.xlollx.songport.data.Settings) { store.updateSettings(f); Scheduler.applyArtistAlerts(ctx) }

    if (s.followedArtists.isNotEmpty() && !Notifications.canPost(ctx)) NoticeCard(
        Icons.Filled.NotificationsOff, stringResource(R.string.alerts_no_permission), stringResource(R.string.settings_notifications_system),
    ) { runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) } }
    if (!s.artistAlerts) NoticeCard(Icons.Filled.NotificationsOff, stringResource(R.string.alerts_off), stringResource(R.string.alerts_turn_on)) {
        update { it.copy(artistAlerts = true) }
    }

    // ---- the search and its suggestions
    var query by rememberSaveable { mutableStateOf("") }
    var remote by remember { mutableStateOf<List<ArtistSuggest.Suggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    val latest = remember(s.followedArtists) { ArtistAlerts.latest(ctx.env) }
    val known = remember(latest) { ArtistSuggest.known(ctx.env) + latest.map { it.artist } }
    LaunchedEffect(query) {
        remote = emptyList(); unreachable = false
        if (query.trim().length < ArtistSuggest.MIN_CHARS) { searching = false; return@LaunchedEffect }
        searching = true
        try {
            // A key typed meanwhile starts over: one question for the name as it stands, a second after the last one at the earliest.
            delay(ArtistSuggest.DEBOUNCE_MS)
            remote = ArtistSuggest.remote(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unreachable = true
        }
        searching = false
    }
    val suggestions = remember(query, remote, s.followedArtists, known) {
        ArtistSuggest.merge(query, s.followedArtists, ArtistSuggest.local(known, query), remote)
    }
    fun follow(sg: ArtistSuggest.Suggestion) {
        update { it.copy(followedArtists = ArtistAlerts.follow(it.followedArtists, sg.name, it.artistAlertKinds, sg.mbid)) }
        query = ""; focus.clearFocus()
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true, shape = RoundedCornerShape(28.dp),
        placeholder = { Text(stringResource(R.string.alerts_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.alerts_search_clear)) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        // Enter follows the first name offered: the artist meant, when the name was typed whole.
        // (While MusicBrainz is still asked, Enter only hides the keyboard: the name as typed is not what is wanted.)
        keyboardActions = KeyboardActions(onDone = { (if (searching) null else suggestions.firstOrNull { !it.followed })?.let { follow(it) } ?: focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().testTag(FOLLOWED_SEARCH_TAG),
    )
    if (suggestions.isNotEmpty()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column {
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.heightIn(min = 4.dp))
            suggestions.forEach { sg -> SuggestionRow(sg) { follow(sg) } }
            if (unreachable) Text(
                stringResource(R.string.alerts_search_offline), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.heightIn(min = 4.dp))
        }
    }

    // ---- the artists followed
    // Names typed by hand (before the suggestions were there, or with MusicBrainz not answering): written as it does, asked once each.
    val unspelled = remember(s.followedArtists) { s.followedArtists.filter { it.mbid == null }.map { it.name } }
    LaunchedEffect(unspelled) {
        store.data.settings.followedArtists.filter { it.mbid == null && it.name !in spellingAsked }.take(3).forEach { f ->
            spellingAsked += f.name
            val hit = try { ArtistSuggest.remote(f.name) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@LaunchedEffect }
            hit.firstOrNull { ArtistAlerts.key(it.name) == ArtistAlerts.key(f.name) }?.let { h ->
                store.updateSettings { it.copy(followedArtists = ArtistAlerts.canonical(it.followedArtists, f.name, h.name, h.mbid)) }
            }
        }
    }
    val followed = remember(s.followedArtists) { ArtistAlerts.sorted(s.followedArtists) }
    if (followed.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(stringResource(R.string.alerts_none), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(stringResource(R.string.alerts_followed_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    } else {
        Text(
            pluralStringResource(R.plurals.alerts_count, followed.size, followed.size), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
        val unfollowed = stringResource(R.string.alerts_unfollowed)
        val undo = stringResource(R.string.unplayable_undo)
        // Two columns of artists where the window has the room (a tablet, a phone on its side).
        val columns = if (LocalWindowLayout.current.width == WidthClass.COMPACT) 1 else 2
        followed.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                row.forEach { f ->
                    FollowedCard(
                        f, s.artistAlerts, latest.filter { ArtistAlerts.key(it.artist) == ArtistAlerts.key(f.name) }.take(2),
                        onMuted = { m -> update { it.copy(followedArtists = ArtistAlerts.setMuted(it.followedArtists, f.name, m)) } },
                        onKinds = { k -> update { it.copy(followedArtists = ArtistAlerts.setKinds(it.followedArtists, f.name, k)) } },
                        onUnfollow = {
                            val at = store.data.settings.followedArtists.indexOfFirst { ArtistAlerts.key(it.name) == ArtistAlerts.key(f.name) }
                            update { it.copy(followedArtists = it.followedArtists.filter { o -> ArtistAlerts.key(o.name) != ArtistAlerts.key(f.name) }) }
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                val r = snackbar.showSnackbar(unfollowed.format(f.name), undo, duration = SnackbarDuration.Long)
                                if (r == SnackbarResult.ActionPerformed) update { it.copy(followedArtists = ArtistAlerts.restore(it.followedArtists, f, at)) }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }

    if (settingsOpen) AlertSettingsSheet(s, onSettingsClose) { update(it) }
}

/** The names whose spelling was already asked of MusicBrainz in this run of the app. */
private val spellingAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/** How the look runs, behind the gear: on or off, how often, on which network, and what the next follows start with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertSettingsSheet(s: com.xlollx.songport.data.Settings, onDismiss: () -> Unit, update: ((com.xlollx.songport.data.Settings) -> com.xlollx.songport.data.Settings) -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.alerts_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp).semantics { heading() })
            PrefSwitch(Icons.Filled.Notifications, stringResource(R.string.alerts_master), stringResource(R.string.alerts_master_summary), s.artistAlerts) { v ->
                update { it.copy(artistAlerts = v) }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                listOf(false to R.string.alerts_daily, true to R.string.alerts_weekly).forEachIndexed { i, (weekly, label) ->
                    SegmentedButton(
                        selected = s.artistAlertsWeekly == weekly, enabled = s.artistAlerts, shape = SegmentedButtonDefaults.itemShape(i, 2),
                        onClick = { update { it.copy(artistAlertsWeekly = weekly) } },
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            PrefSwitch(Icons.Filled.Wifi, stringResource(R.string.alerts_wifi), null, s.artistAlertsWifiOnly, enabled = s.artistAlerts) { v ->
                update { it.copy(artistAlertsWifiOnly = v) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.alerts_defaults), style = MaterialTheme.typography.titleSmall)
                KindChips(s.artistAlertKinds) { k -> update { it.copy(artistAlertKinds = k) } }
                val country = Concerts.savedCountry(ctx.env) ?: defaultConcertCountry(ctx)
                Text(
                    stringResource(R.string.alerts_concerts_where, Locale("", country).getDisplayCountry(ctx.resources.configuration.locales[0])),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
