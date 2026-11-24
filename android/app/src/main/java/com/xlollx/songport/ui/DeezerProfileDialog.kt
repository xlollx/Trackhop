package com.xlollx.songport.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.DeezerBridgeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Which profile?" for a Deezer Family account (#82): the profiles the holder may act as, with their
 * pictures; the chosen one is kept by the connectors for this account only, and every sync through
 * it reads and writes that profile. [firstTime]: asked right after the sign-in, where "Later" keeps
 * the holder's own profile (and so is not asked again); from the account's menu it only closes.
 * [preloaded]: the list, when the caller already read it.
 */
@Composable
fun DeezerProfileDialog(
    p: DeezerBridgeProvider,
    firstTime: Boolean,
    preloaded: List<DeezerBridgeProvider.Profile>? = null,
    onDismiss: () -> Unit,
    onChosen: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(preloaded) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(preloaded?.firstOrNull { it.selected }?.id) }
    LaunchedEffect(p.id) {
        if (profiles == null) runCatching { withContext(Dispatchers.IO) { p.profiles(ctx.env) } }
            .onSuccess { list -> profiles = list; picked = list.firstOrNull { it.selected }?.id }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    fun keep(profile: DeezerBridgeProvider.Profile, quiet: Boolean) {
        saving = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { p.setProfile(ctx.env, profile) } }
                .onSuccess { onChosen() }
                .onFailure { if (quiet) onDismiss() else { error = ctx.getString(R.string.deezer_profile_failed); saving = false } }
        }
    }
    val holder = profiles?.firstOrNull { it.holder }
    FitDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.deezer_profile_title)) },
        text = {
            Column {
                Text(stringResource(R.string.deezer_profile_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp)) }
                val list = profiles
                if (list == null && error == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.loading))
                }
                list?.forEach { pr ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !saving) { picked = pr.id }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == pr.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        ProfileAvatar(pr.name, pr.picture)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(pr.name, style = MaterialTheme.typography.bodyLarge)
                            if (pr.holder) Text(stringResource(R.string.deezer_profile_holder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val choice = profiles?.firstOrNull { it.id == picked }
            if (profiles != null) TextButton(enabled = !saving && choice != null, onClick = { choice?.let { keep(it, quiet = false) } }) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp)) else Text(stringResource(R.string.deezer_profile_use))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = {
                // Asked after the sign-in: "Later" keeps the holder's own profile, as before, and is not asked again.
                if (firstTime && holder != null && error == null) keep(holder, quiet = true) else onDismiss()
            }) { Text(stringResource(if (firstTime) R.string.deezer_profile_later else R.string.cancel)) }
        },
    )
}

/** A profile's round picture from Deezer; its initial until it arrives, and when there is none. */
@Composable
private fun ProfileAvatar(name: String, picture: String?) {
    var bmp by remember(picture) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(picture) { if (picture != null) bmp = Cards.bitmap(picture, 120) }
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
