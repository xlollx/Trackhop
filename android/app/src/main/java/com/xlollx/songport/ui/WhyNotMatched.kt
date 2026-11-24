package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.MatchExplain

/**
 * "Why wasn't it matched?" (2.2): the closest candidates the target's search gave, each with its
 * score against the sync's threshold and the parts that make it up. [onUse] keeps one as the match.
 */
@Composable
fun WhyNotMatched(explained: List<MatchExplain.Explained>, threshold: Double, busy: Boolean, onUse: (Track) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.why_not_matched), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.why_needs, pct(threshold)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (explained.isEmpty()) {
            Text(stringResource(R.string.why_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        explained.forEach { e ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackLine(e.track, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                val color = when {
                    e.matched -> Tones.onSuccessContainer()
                    e.score >= threshold - 0.15 -> toneContent(Tone.Warning)
                    else -> MaterialTheme.colorScheme.error
                }
                Text("${pct(e.score)}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
            }
            Axis(stringResource(R.string.why_axis_title), e.title, "${pct(e.title)}%")
            e.artist?.let { Axis(stringResource(R.string.why_axis_artist), it, "${pct(it)}%") }
            Axis(stringResource(R.string.why_axis_length), e.length, "×%.2f".format(e.length))
            Axis(stringResource(R.string.why_axis_version), e.version, "×%.2f".format(e.version))
            Axis(stringResource(R.string.why_axis_album), e.album, "×%.2f".format(e.album))
            val reasons = e.reasons.map { r ->
                when (r) {
                    MatchExplain.Reason.SAME_ISRC -> stringResource(R.string.why_reason_same_isrc)
                    MatchExplain.Reason.SAME_RECORDING -> stringResource(R.string.why_reason_same_recording)
                    MatchExplain.Reason.OTHER_TITLE -> stringResource(R.string.why_reason_other_title)
                    MatchExplain.Reason.OTHER_ARTIST -> stringResource(R.string.why_reason_other_artist)
                    MatchExplain.Reason.LENGTH -> stringResource(R.string.why_reason_length, MatchExplain.signedLength(e.lengthDiffMs))
                    MatchExplain.Reason.VERSION -> stringResource(R.string.why_reason_version)
                    MatchExplain.Reason.ALBUM -> stringResource(R.string.why_reason_album)
                }
            }
            if (reasons.isNotEmpty()) Text(reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy, onClick = { onUse(e.track) }) { Text(stringResource(R.string.why_use)) }
            }
        }
        Text(stringResource(R.string.why_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun pct(v: Double): Int = Math.round(v.coerceIn(0.0, 1.0) * 100).toInt()

/** One part of the score: its name, a bar (low ones in the error colour) and its value. */
@Composable
private fun Axis(name: String, value: Double, shown: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat())
                    .background(if (value < 0.9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            )
        }
        Text(shown, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(46.dp).padding(start = 6.dp))
    }
}
