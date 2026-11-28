package com.xlollx.songport.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.BridgePlugin
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.SpotifyProvider
import com.xlollx.songport.providers.YouTubeProvider

/**
 * The store builds (Google Play, AppGallery) reach the services only through their official APIs.
 * Where nothing else is installed, the first page of the introduction and the key screen of the
 * services that other versions reach without a developer key say so, plainly, with a link to the
 * site's page on the versions. A fact, not a complaint: no store and no policy is blamed, and no
 * other app is named or linked from here (the site explains the rest).
 */
object OfficialOnly {
    const val VERSIONS_URL = "https://trackhop.org/guide/versions"

    /** The official connectors that other versions also reach without a developer key. */
    private val KEYLESS_ELSEWHERE = setOf(YouTubeProvider.SERVICE, SpotifyProvider.SERVICE, AppleMusicProvider.SERVICE, DeezerProvider.SERVICE)

    /** True on a store build with no connector plugin installed. */
    fun applies(ctx: android.content.Context): Boolean = !BridgePlugin.installed(ctx)

    fun keylessElsewhere(serviceId: String): Boolean = serviceId in KEYLESS_ELSEWHERE
}

/** The notice: [text] and the link to the site's page on the versions. Shown only where [OfficialOnly.applies]. */
@Composable
fun OfficialOnlyNotice(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val show = remember { OfficialOnly.applies(ctx) }
    if (!show) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OfficialOnly.VERSIONS_URL))) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.official_only_link)) }
            }
        }
    }
}
