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
 * Concerts: where a date's tickets are. Vivaticket's and TicketOne's dates come from their sites,
 * unofficially (only where unofficial sources are allowed), and say so.
 */
@Composable
internal fun ticketsLabel(source: Concerts.Source): String = when (source) {
    Concerts.Source.TICKETMASTER -> stringResource(R.string.concerts_on_ticketmaster)
    Concerts.Source.VIVATICKET -> stringResource(R.string.concerts_on_vivaticket)
    Concerts.Source.TICKETONE -> stringResource(R.string.concerts_on_ticketone)
    else -> source.label
}

/** The other ticket sites selling the same date, one link each. */
@Composable
internal fun AlsoOnLinks(e: Concerts.Event) {
    val ctx = LocalContext.current
    e.alsoOn.forEach { l ->
        TextButton(onClick = { openUrl(ctx, l.url) }) { Text(ticketsLabel(l.source), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
    }
}

/** Under the results, when some dates come from an unofficial source: it may stop working. */
@Composable
internal fun UnofficialSourcesNote(r: Concerts.Result) {
    val used = r.artists.flatMap { a -> a.events.flatMap { e -> listOf(e.source) + e.alsoOn.map { it.source } } }.toSet()
    listOf(Concerts.Source.VIVATICKET to R.string.concerts_sources_unofficial, Concerts.Source.TICKETONE to R.string.concerts_sources_ticketone).forEach { (source, note) ->
        if (source in used) Text(stringResource(note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
