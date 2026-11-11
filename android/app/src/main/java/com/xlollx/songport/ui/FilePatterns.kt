package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.sync.NamePatterns

/** Templates offered as a start: tapping one adds it as a new line. */
private val EXAMPLES = listOf(
    "{artist}/{album}/{track} {title}",
    "{artist} - {album} - {track} {title}",
    "{artist}/{year} - {album}/{track} {title}",
    "Music/{artist}/**/{album}/[Disc {disc}/]{track} {title}",
    "{artist} - {title}[ ({year})]",
    "{title} ({artist})",
    "{artist} - {title}",
)

/**
 * The user's own patterns for names in imported files (#49): one per line, examples to add, the
 * errors of the lines that do not compile, and a line from a file tried against them as it is typed.
 */
@Composable
internal fun FilePatternsDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.joinToString("\n")) }
    var sample by rememberSaveable { mutableStateOf("") }
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val compiled = remember(text) { lines.map { NamePatterns.compile(it) } }
    val errors = compiled.mapIndexedNotNull { i, r -> r.exceptionOrNull()?.let { i + 1 to (it.message ?: "?") } }
    val patterns = compiled.mapNotNull { it.getOrNull() }
    val ctx = LocalContext.current
    val close = rememberDiscardGuard(lines != initial.map { it.trim() }.filter { it.isNotEmpty() }, onClose = onDismiss)
    FitDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.files_patterns_title)) },
        text = {
            Column {
                Text(stringResource(R.string.files_patterns_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.files_patterns_title)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = errors.isNotEmpty(),
                    supportingText = errors.takeIf { it.isNotEmpty() }?.let { e ->
                        { Text(e.joinToString("\n") { (n, why) -> ctx.getString(R.string.files_patterns_error, n, why) }) }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.files_patterns_examples), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EXAMPLES.forEach { ex ->
                        AssistChip(
                            onClick = { text = (text.trimEnd().takeIf { it.isNotEmpty() }?.let { it + "\n" } ?: "") + ex },
                            label = { Text(ex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = sample, onValueChange = { sample = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.files_patterns_try)) },
                )
                if (sample.isNotBlank()) {
                    val r = NamePatterns.read(sample, patterns)
                    Text(
                        if (r == null) stringResource(R.string.files_patterns_nomatch)
                        else stringResource(R.string.files_patterns_result, r.artist.ifEmpty { "–" }, r.album.ifEmpty { "–" }, r.title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = patterns.isNotEmpty() && errors.isEmpty(), onClick = { onSave(lines) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
