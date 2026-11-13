package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.net.Connectivity
import com.xlollx.songport.net.NetError

/** The text of a network failure of [kind]; null when it is not one. */
fun netTextId(kind: NetError.Kind): Int? = when (kind) {
    NetError.Kind.NONE -> null
    NetError.Kind.OFFLINE -> R.string.net_offline
    NetError.Kind.TIMEOUT -> R.string.net_timeout
    NetError.Kind.UNREACHABLE -> R.string.net_unreachable
}

/**
 * Plain words for [e] when the connection failed ("No internet connection", "The service didn't answer
 * in time…"), null otherwise. The platform's own line goes to the diagnostic log, where it helps.
 */
fun netWords(ctx: Context, e: Throwable?): String? {
    val id = netTextId(NetError.kind(e)) ?: return null
    runCatching { Diagnostics.log(ctx, "net", "${e?.javaClass?.simpleName}: ${e?.message?.take(200)}") }
    return ctx.getString(id)
}

/** What a failure says to the user: plain words for the connection, its own message otherwise (as before). */
fun Throwable.userMessage(ctx: Context): String = netWords(ctx, this) ?: message ?: ctx.getString(R.string.error_generic)

/** [raw], an error known only by its text (a stored report, a reply from another process), in plain words when it is the connection's. */
fun userText(ctx: Context, raw: String?): String? = netTextId(NetError.messageKind(raw))?.let { ctx.getString(it) } ?: raw

/** True while the phone has internet (or it is not known): follows the system. */
@Composable
fun rememberOnline(): Boolean {
    val online by Connectivity.online.collectAsState()
    return online
}

/** The offline banner (for the UI tests). */
const val OFFLINE_BANNER_TAG = "offline-banner"

/**
 * A slim tonal line at the top of a tab while there is no internet: nothing is blocked, it only
 * says why things wait. TalkBack reads it when it appears; it goes by itself when the connection is back.
 */
@Composable
fun OfflineBanner(visible: Boolean, modifier: Modifier = Modifier, text: String = stringResource(R.string.net_offline)) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(OFFLINE_BANNER_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * Where a page could not load for the connection: a calm empty state (the cloud crossed out, one
 * sentence, Try again) instead of an error in red. [message] says what failed when online (too slow,
 * out of reach); offline it says what is needed.
 */
@Composable
fun NetErrorState(message: String?, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val text = if (!rememberOnline()) stringResource(R.string.offline_empty) else message ?: stringResource(R.string.offline_empty)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onRetry != null) FilledTonalButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.net_try_again))
        }
    }
}

/** True when [message] is one of the plain network texts above (a failure already said in those words). */
fun isNetText(ctx: Context, message: String?): Boolean =
    message != null && listOf(R.string.net_offline, R.string.net_timeout, R.string.net_unreachable, R.string.offline_empty).any { ctx.getString(it) == message }
