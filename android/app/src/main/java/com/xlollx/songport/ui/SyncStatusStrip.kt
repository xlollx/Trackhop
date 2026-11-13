package com.xlollx.songport.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.sync.SyncSummary
import java.text.DateFormat

/**
 * The one line that heads the sync list, where the tagline's card was: how the syncs stand
 * ([SyncSummary]: "All up to date", "1 failed · 3 to fix", "2 syncs running"…), when the last one ran,
 * and "Your numbers" at its end ([onStats]; null until a sync has completed). With something to show
 * for it ([SyncSummary.filter]) the line is a button: [onShow] puts that filter on, and takes it off
 * again while it is on ([shown]).
 */
@Composable
fun SyncStatusStrip(
    summary: SyncSummary, total: Int, lastRun: Long, shown: Boolean,
    onShow: () -> Unit, onStats: (() -> Unit)?, modifier: Modifier = Modifier,
    /** No internet: the line says so (and that the syncs wait for it), over everything else, until the connection is back. */
    offline: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val failed = summary.kind == SyncSummary.Kind.ATTENTION && summary.first > 0
    val (icon, tint) = if (offline) Icons.Filled.CloudOff to scheme.onSurfaceVariant else when (summary.kind) {
        SyncSummary.Kind.RUNNING, SyncSummary.Kind.RUNNING_PAUSED -> Icons.Filled.Sync to scheme.primary
        SyncSummary.Kind.PAUSED -> Icons.Filled.Pause to scheme.onSurfaceVariant
        SyncSummary.Kind.ATTENTION -> Icons.Filled.Warning to if (failed) scheme.error else scheme.tertiary
        SyncSummary.Kind.ALL_OK -> Icons.Filled.CheckCircle to Tones.onSuccessContainer()
        SyncSummary.Kind.IDLE -> Icons.Filled.Schedule to scheme.onSurfaceVariant
    }
    val main = if (offline) stringResource(R.string.offline_banner) else when (summary.kind) {
        // Shorter than the Sync-all pill's own words just under it ("3 syncs running"): the same count, said once each way.
        SyncSummary.Kind.RUNNING -> pluralStringResource(R.plurals.status_running, summary.first, summary.first)
        SyncSummary.Kind.RUNNING_PAUSED -> pluralStringResource(R.plurals.status_running, summary.first, summary.first) + " · " + pluralStringResource(R.plurals.status_paused, summary.second, summary.second)
        SyncSummary.Kind.PAUSED -> pluralStringResource(R.plurals.status_paused, summary.first, summary.first)
        SyncSummary.Kind.ATTENTION -> listOfNotNull(
            summary.first.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.status_failed, it, it) },
            summary.second.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.status_to_fix, it, it) },
        ).joinToString(" · ")
        SyncSummary.Kind.ALL_OK -> stringResource(R.string.status_all_ok)
        SyncSummary.Kind.IDLE -> pluralStringResource(R.plurals.hero_jobs, total, total)
    }
    // When the last sync ran, while nothing more pressing is said.
    val settled = summary.kind == SyncSummary.Kind.ALL_OK || summary.kind == SyncSummary.Kind.IDLE
    val last = if (!offline && settled && lastRun > 0) stringResource(R.string.status_last, lastSyncLabel(lastRun)) else null
    val canShow = summary.filter.isNotEmpty() && !offline
    // A large font: the line may take two rather than lose its end.
    val lines = if (LocalDensity.current.fontScale > 1.3f) 2 else 1
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                .then(if (canShow) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(if (shown) R.string.filter_clear else R.string.crowd_show), onClick = onShow) else Modifier)
                .padding(start = 4.dp, end = 8.dp).testTag(SYNC_STATUS_TAG)
                // Said by TalkBack when the connection goes ("Back online" is the snackbar's).
                .then(if (offline) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            // When the last sync ran is said while the line has room for it: where it does not (a narrow
            // window, a large font, a language with longer words) the status alone is whole, not both cut short.
            var roomForLast by remember(main, last, lines, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp) { mutableStateOf(true) }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.SemiBold)) { append(main) }
                    if (last != null && roomForLast) withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(" · "); append(last) }
                },
                style = MaterialTheme.typography.bodyMedium, maxLines = lines, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow && last != null && roomForLast) roomForLast = false },
                modifier = Modifier.weight(1f, fill = false),
            )
            if (canShow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        }
        if (onStats != null) Row(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onStats).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.stats_hero_link), color = scheme.primary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = scheme.primary)
        }
    }
}

/** The status line of the sync list (for the UI tests). */
const val SYNC_STATUS_TAG = "sync-status"

/**
 * When the last sync ran, as short as it can be said: the time alone today ("7:32 AM"), "Yesterday",
 * otherwise the date; each as the system writes it in the user's language.
 */
internal fun lastSyncLabel(epoch: Long, now: Long = System.currentTimeMillis()): String = when {
    DateUtils.isToday(epoch) -> DateUtils.formatSameDayTime(epoch, now, DateFormat.SHORT, DateFormat.SHORT).toString()
    DateUtils.isToday(epoch + DateUtils.DAY_IN_MILLIS) -> DateUtils.getRelativeTimeSpanString(epoch, now, DateUtils.DAY_IN_MILLIS).toString()
    else -> DateFormat.getDateInstance(DateFormat.SHORT).format(java.util.Date(epoch))
}
