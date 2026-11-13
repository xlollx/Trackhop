package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider

/**
 * Three calls to a connected service, one after the other: who is signed in, the playlist list, one
 * search. Each row says whether it worked and, if not, what the service answered: the first thing
 * to look at when a sync stops, without waiting for one to fail.
 */
@Composable
fun ConnectionTestDialog(provider: MusicProvider, onClose: () -> Unit) {
    val ctx = LocalContext.current
    class Step(val label: Int, val state: Int, val detail: String)
    var steps by remember { mutableStateOf(listOf(Step(R.string.test_profile, 0, ""), Step(R.string.test_playlists, 0, ""), Step(R.string.test_search, 0, ""))) }
    fun set(i: Int, ok: Boolean, detail: String) { steps = steps.mapIndexed { j, s -> if (j == i) Step(s.label, if (ok) 1 else 2, detail) else s } }

    // Without internet the three calls cannot say anything about the service: the dialog says so
    // instead, and runs them once the connection is back (or on Try again).
    val online = rememberOnline()
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(provider.id, attempt, online) {
        if (!online) return@LaunchedEffect
        steps = steps.map { Step(it.label, 0, "") }
        val app = ctx.applicationContext
        runCatching { provider.accountName(app) }
            .onSuccess { set(0, true, it ?: ctx.getString(R.string.test_ok)) }
            .onFailure { set(0, false, netWords(ctx, it) ?: it.message ?: it.javaClass.simpleName) }
        runCatching { provider.playlists(app) }
            .onSuccess { set(1, true, ctx.getString(R.string.test_playlists_ok, it.size)) }
            .onFailure { set(1, false, netWords(ctx, it) ?: it.message ?: it.javaClass.simpleName) }
        val sample = Track(id = "", title = "Bohemian Rhapsody", artists = listOf("Queen"), album = "A Night at the Opera")
        runCatching { provider.search(app, sample) }
            .onSuccess { r -> set(2, r.isNotEmpty(), r.firstOrNull()?.toString() ?: ctx.getString(R.string.test_search_empty)) }
            .onFailure { set(2, false, netWords(ctx, it) ?: it.message ?: it.javaClass.simpleName) }
    }

    FitDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.test_title, provider.label(ctx))) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (!online) {
                    NetErrorState(null, onRetry = { attempt++ })
                    return@Column
                }
                steps.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        when (s.state) {
                            0 -> CircularProgressIndicator(Modifier.size(18.dp).padding(top = 2.dp))
                            1 -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            else -> Icon(Icons.Filled.Error, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(stringResource(s.label), style = MaterialTheme.typography.bodyLarge)
                            if (s.detail.isNotEmpty()) Text(
                                s.detail, style = MaterialTheme.typography.bodySmall,
                                color = if (s.state == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.test_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ok)) } },
    )
}
