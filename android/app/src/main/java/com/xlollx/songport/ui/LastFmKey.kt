package com.xlollx.songport.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.providers.LastFmProvider

/**
 * Loving tracks on Last.fm signs every call with the shared secret of an API key. The GitHub and
 * F-Droid builds carry no key of ours (F-Droid rebuilds them identical from the source), so the
 * user makes one on Last.fm (free, a minute) and pastes the key and its secret right here, next to
 * the password, instead of hunting for the "use your own key" page.
 */
object LastFmKey {
    const val CREATE_URL = "https://www.last.fm/api/account/create"
    const val ACCOUNTS_URL = "https://www.last.fm/api/accounts"

    /** True when this build or the user already gives Last.fm a key and its shared secret. */
    fun complete(ctx: android.content.Context): Boolean {
        val s = Store.get(ctx).data.settings
        val key = s.clientIds[LastFmProvider.SERVICE]?.trim().orEmpty().ifEmpty { BuildConfig.LASTFM_API_KEY }
        val secret = s.clientSecrets[LastFmProvider.SERVICE]?.trim().orEmpty().ifEmpty { BuildConfig.LASTFM_SHARED_SECRET }
        return key.isNotBlank() && secret.isNotBlank()
    }

    /** The user's own key, as already saved (blank when the build's is used). */
    fun savedKey(ctx: android.content.Context) = Store.get(ctx).data.settings.clientIds[LastFmProvider.SERVICE].orEmpty()
    fun savedSecret(ctx: android.content.Context) = Store.get(ctx).data.settings.clientSecrets[LastFmProvider.SERVICE].orEmpty()

    /** Saves the key and the secret the user pasted (a blank one leaves what is there). */
    fun save(ctx: android.content.Context, key: String, secret: String) {
        val k = key.trim(); val s = secret.trim()
        Store.get(ctx).updateSettings { st ->
            st.copy(
                clientIds = if (k.isEmpty()) st.clientIds else st.clientIds + (LastFmProvider.SERVICE to k),
                clientSecrets = if (s.isEmpty()) st.clientSecrets else st.clientSecrets + (LastFmProvider.SERVICE to s),
            )
        }
    }
}

/** The key and shared secret, with the way to get them: a button to Last.fm's page and paste buttons. */
@Composable
internal fun LastFmKeyCard(key: String, onKey: (String) -> Unit, secret: String, onSecret: (String) -> Unit, enabled: Boolean) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lastfm_key_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(stringResource(R.string.lastfm_key_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { open(LastFmKey.CREATE_URL) }, enabled = enabled) { Text(stringResource(R.string.lastfm_key_create)) }
                TextButton(onClick = { open(LastFmKey.ACCOUNTS_URL) }, enabled = enabled) { Text(stringResource(R.string.lastfm_key_have)) }
            }
            @Composable
            fun field(value: String, onChange: (String) -> Unit, label: Int) = OutlinedTextField(
                value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
                label = { Text(stringResource(label)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.trim()?.let(onChange) }, enabled = enabled) {
                        Icon(Icons.Filled.ContentPaste, stringResource(R.string.lastfm_key_paste))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            field(key, onKey, R.string.lastfm_key_api)
            field(secret, onSecret, R.string.lastfm_key_secret)
        }
    }
}
