package com.xlollx.songport.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xlollx.songport.R
import com.xlollx.songport.sync.Concerts

/**
 * Concerts: where a date's tickets are. Vivaticket's dates come from its site, unofficially (only
 * the builds that allow unofficial sources read it), and say so.
 */
@Composable
internal fun ticketsLabel(source: Concerts.Source): String = when (source) {
    Concerts.Source.TICKETMASTER -> stringResource(R.string.concerts_on_ticketmaster)
    Concerts.Source.VIVATICKET -> stringResource(R.string.concerts_on_vivaticket)
    else -> source.label
}

/** The other ticket sites selling the same date, one link each. */
@Composable
internal fun AlsoOnLinks(e: Concerts.Event) {
    val ctx = LocalContext.current
    e.alsoOn.forEach { l ->
        TextButton(onClick = { openUrl(ctx, l.url) }) { Text(ticketsLabel(l.source), style = MaterialTheme.typography.labelMedium) }
    }
}

/** Under the results, when some dates come from an unofficial source: it may stop working. */
@Composable
internal fun UnofficialSourcesNote(r: Concerts.Result) {
    val unofficial = r.artists.any { a -> a.events.any { e -> e.source.unofficial || e.alsoOn.any { it.source.unofficial } } }
    if (unofficial) Text(
        stringResource(R.string.concerts_sources_unofficial),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
