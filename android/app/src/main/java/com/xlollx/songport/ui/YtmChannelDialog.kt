package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.YouTubeBridgeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the YouTube Music cards remember about profiles (own channel or brand account), per
 * connector: whether the choice was offered after this sign-in, and the warning that the profile in
 * use is empty while another holds music (shown on the card until a choice is made or kept).
 * The official route: whether its channel was checked after sign-in, and found empty.
 */
object YtmProfiles {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ytm_profiles", Context.MODE_PRIVATE)

    fun asked(ctx: Context, id: String, account: String?): Boolean = prefs(ctx).getBoolean("asked:$id:${account.orEmpty()}", false)
    fun markAsked(ctx: Context, id: String, account: String?) { prefs(ctx).edit().putBoolean("asked:$id:${account.orEmpty()}", true).apply() }

    /** The warning on the card: the empty profile's name and the one the music seems to be on. */
    fun warning(ctx: Context, id: String): Pair<String, String>? =
        prefs(ctx).getString("warn:$id", null)?.split('\n')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun setWarning(ctx: Context, id: String, current: String?, better: String?) {
        prefs(ctx).edit().apply { if (current == null || better == null) remove("warn:$id") else putString("warn:$id", "$current\n$better") }.apply()
    }

    /** Official route: null = not checked for this account yet, true = its channel looked empty (and the note was not dismissed). */
    fun officialEmpty(ctx: Context, id: String, account: String?): Boolean? =
        prefs(ctx).getString("official:$id:${account.orEmpty()}", null)?.let { it == "empty" }
    fun setOfficialEmpty(ctx: Context, id: String, account: String?, empty: Boolean?) {
        prefs(ctx).edit().apply { val k = "official:$id:${account.orEmpty()}"; if (empty == null) remove(k) else putString(k, if (empty) "empty" else "ok") }.apply()
    }
}

/** "24 playlists · 312 liked songs", "no playlists, no liked songs"; null when the connector gave no counts. */
@Composable
private fun libraryLine(c: YouTubeBridgeProvider.Channel): String? {
    if (c.playlists < 0 && c.likedSongs < 0) return null
    if (c.playlists == 0 && c.likedSongs == 0) return stringResource(R.string.ytm_profile_nothing)
    return listOfNotNull(
        when { c.playlists < 0 -> null; c.playlists == 0 -> stringResource(R.string.ytm_profile_no_playlists); else -> pluralStringResource(R.plurals.ytm_profile_playlists, c.playlists, c.playlists) },
        when { c.likedSongs < 0 -> null; c.likedSongs == 0 -> stringResource(R.string.ytm_profile_no_liked); else -> pluralStringResource(R.plurals.ytm_profile_liked, c.likedSongs, c.likedSongs) },
    ).joinToString(" · ")
}

/**
 * The profile a YouTube Music account acts as: its own channel, or one of its brand accounts, where
 * the playlists and liked songs may live (issue #41, and the Reddit case of likes on the wrong one).
 * Each profile with how much music it holds; when the one in use is empty and another is not, a
 * warning and that one offered first. With [auto] (right after sign-in) an account with a single
 * profile, or one that cannot be read, closes without a word. A choice is kept in the connectors,
 * for this account only, and the card refreshes ([onChosen]).
 */
@Composable
fun YtmChannelDialog(p: YouTubeBridgeProvider, account: String?, auto: Boolean = false, onDismiss: () -> Unit, onChosen: () -> Unit) {
    val ctx = LocalContext.current
    var channels by remember { mutableStateOf<List<YouTubeBridgeProvider.Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    fun close() { YtmProfiles.markAsked(ctx, p.id, account); onDismiss() }
    LaunchedEffect(p.id) {
        runCatching { withContext(Dispatchers.IO) { p.channels(ctx.env, library = true) } }
            .onSuccess { list ->
                val current = list.firstOrNull { it.selected } ?: list.firstOrNull { it.id.isEmpty() }
                YtmProfiles.setWarning(ctx, p.id, current?.name, p.betterChannel(list)?.name)
                if (auto && list.size < 2) close() else channels = list
            }
            .onFailure { if (auto) close() else error = ctx.getString(R.string.ytm_channel_failed, it.message ?: it.javaClass.simpleName) }
    }
    // A chosen profile is written to the connectors off the main thread; the dialog closes when it is kept.
    var chosen by remember { mutableStateOf<YouTubeBridgeProvider.Channel?>(null) }
    chosen?.let { c ->
        LaunchedEffect(c.id) {
            saving = true
            runCatching { withContext(Dispatchers.IO) { p.setChannel(ctx.env, c) } }
                .onSuccess { YtmProfiles.setWarning(ctx, p.id, null, null); YtmProfiles.markAsked(ctx, p.id, account); onChosen() }
                .onFailure { error = ctx.getString(R.string.ytm_channel_failed, it.message ?: it.javaClass.simpleName); saving = false; chosen = null }
        }
    }
    val list = channels
    // Right after sign-in the profiles are read in the background: nothing on screen until there is a choice to make.
    if (auto && list == null && error == null) return
    val current = list?.let { l -> l.firstOrNull { it.selected } ?: l.firstOrNull { it.id.isEmpty() } ?: l.firstOrNull() }
    val better = list?.let { p.betterChannel(it) }
    val selected = list?.firstOrNull { it.id == (picked ?: current?.id) }
    val warned = current != null && better != null && selected?.id == current.id
    FitDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text(stringResource(R.string.ytm_profile_title)) },
        text = {
            Column {
                Text(
                    if (list != null && list.size > 1) stringResource(R.string.ytm_profile_body, list.size) else stringResource(R.string.ytm_profile_body_one),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (current != null && better != null) {
                    PageNote(stringResource(R.string.ytm_profile_wrong, current.name, better.name), tone = Tone.Warning)
                    Spacer(Modifier.height(4.dp))
                }
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.loading)) }
                    else -> list.forEach { c ->
                        val on = c.id == selected?.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .then(if (on) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                                .clickable(enabled = !saving) { picked = c.id }.padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = on, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                    if (better != null && c.id == current?.id) { Spacer(Modifier.width(6.dp)); StatusPill(stringResource(R.string.ytm_profile_empty), Tone.Warning) }
                                    if (c.id == better?.id) { Spacer(Modifier.width(6.dp)); StatusPill(stringResource(R.string.ytm_profile_here), Tone.Ok) }
                                }
                                val sub = listOfNotNull(if (c.id.isEmpty()) stringResource(R.string.ytm_channel_own) else c.byline, libraryLine(c)).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (list != null && better == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ytm_profile_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            when {
                list == null || selected == null -> TextButton(onClick = { close() }) { Text(stringResource(android.R.string.cancel)) }
                // The warning's way out: the profile the music is on.
                warned -> Button(enabled = !saving, onClick = { chosen = better }) { Text(stringResource(R.string.ytm_profile_use, better!!.name)) }
                else -> Button(enabled = !saving, onClick = { if (selected.id == current?.id) close() else chosen = selected }) { Text(stringResource(R.string.ytm_profile_continue, selected.name)) }
            }
        },
        dismissButton = if (warned) {
            {
                // Kept on purpose: the warning goes, and the card does not say it again.
                TextButton(enabled = !saving, onClick = { YtmProfiles.setWarning(ctx, p.id, null, null); close() }) { Text(stringResource(R.string.ytm_profile_keep, current!!.name)) }
            }
        } else null,
    )
}
