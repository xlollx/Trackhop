package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.ArtistAlerts
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.Scheduler
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

private val KIND_RES = mapOf(
    ArtistAlerts.Kind.RELEASE to R.string.alerts_kind_releases, ArtistAlerts.Kind.CONCERT to R.string.alerts_kind_concerts,
    ArtistAlerts.Kind.MERCH to R.string.alerts_kind_merch, ArtistAlerts.Kind.RECORD to R.string.alerts_kind_records,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(kinds: ArtistAlerts.Kinds, enabled: Boolean = true, onChange: (ArtistAlerts.Kinds) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ArtistAlerts.Kind.entries.forEach { k ->
            FilterChip(selected = kinds[k], enabled = enabled, onClick = { onChange(kinds.with(k, !kinds[k])) }, label = { Text(stringResource(KIND_RES.getValue(k))) })
        }
    }
}

/**
 * Discover › Followed artists: the artists followed (from the bells in the other pages, or by
 * name), what each is checked for, the last news found, and how often the look runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowedArtistsPage() {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val s = store.state.collectAsState().value.settings
    fun update(f: (com.xlollx.songport.data.Settings) -> com.xlollx.songport.data.Settings) { store.updateSettings(f); Scheduler.applyArtistAlerts(ctx) }

    Text(stringResource(R.string.alerts_followed_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (s.followedArtists.isNotEmpty() && !Notifications.canPost(ctx)) {
        Text(stringResource(R.string.alerts_no_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
        }) { Text(stringResource(R.string.settings_notifications_system)) }
    }

    // An artist by name: one not in any playlist yet.
    var name by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.alerts_add_hint)) }, singleLine = true, modifier = Modifier.weight(1f))
        Button(enabled = name.isNotBlank() && !ArtistAlerts.isFollowed(s.followedArtists, name), onClick = { toggleFollow(ctx, name); name = "" }) {
            Text(stringResource(R.string.alerts_add))
        }
    }

    // The last news found, each to its page.
    val latest = remember(s.followedArtists) { ArtistAlerts.latest(ctx.env) }
    if (latest.isNotEmpty()) {
        Text(stringResource(R.string.alerts_latest), style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                latest.forEach { n ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = n.url.isNotBlank()) { openUrl(ctx, n.url) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(ArtistAlerts.phrase(ctx.env, n).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (s.followedArtists.isEmpty()) Text(stringResource(R.string.alerts_none), style = MaterialTheme.typography.bodyMedium)
    s.followedArtists.forEach { f ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { toggleFollow(ctx, f.name) }) { Icon(Icons.Filled.Close, stringResource(R.string.alerts_unfollow, f.name)) }
                }
                KindChips(f.kinds, enabled = s.artistAlerts) { k -> update { it.copy(followedArtists = ArtistAlerts.setKinds(it.followedArtists, f.name, k)) } }
            }
        }
    }

    // How the look runs.
    Text(stringResource(R.string.alerts_settings), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            PrefSwitch(Icons.Filled.Notifications, stringResource(R.string.alerts_master), stringResource(R.string.alerts_master_summary), s.artistAlerts) { v ->
                update { it.copy(artistAlerts = v) }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !s.artistAlertsWeekly, enabled = s.artistAlerts, onClick = { update { it.copy(artistAlertsWeekly = false) } }, label = { Text(stringResource(R.string.alerts_daily)) })
                    FilterChip(selected = s.artistAlertsWeekly, enabled = s.artistAlerts, onClick = { update { it.copy(artistAlertsWeekly = true) } }, label = { Text(stringResource(R.string.alerts_weekly)) })
                }
            }
            PrefSwitch(Icons.Filled.Wifi, stringResource(R.string.alerts_wifi), null, s.artistAlertsWifiOnly, enabled = s.artistAlerts) { v ->
                update { it.copy(artistAlertsWifiOnly = v) }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.alerts_defaults), style = MaterialTheme.typography.bodyMedium)
                KindChips(s.artistAlertKinds, enabled = s.artistAlerts) { k -> store.updateSettings { it.copy(artistAlertKinds = k) } }
                val country = Concerts.savedCountry(ctx.env) ?: defaultConcertCountry(ctx)
                Text(
                    stringResource(R.string.alerts_concerts_where, Locale("", country).getDisplayCountry(ctx.resources.configuration.locales[0])),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
