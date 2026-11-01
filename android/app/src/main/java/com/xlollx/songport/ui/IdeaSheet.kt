package com.xlollx.songport.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R

/** Where ideas go by email: forwarded to the developer by the domain's mail routing. */
internal const val IDEAS_EMAIL = "help@trackhop.org"

/** The open ideas on GitHub, the most voted first: before writing one, the ones already there. */
internal const val IDEAS_URL = "https://github.com/xlollx/Trackhop/issues?q=is%3Aissue%20is%3Aopen%20label%3Aenhancement%20sort%3Areactions-%2B1-desc"

/** The services a suggestion can be about, as the issue form lists them. */
private val IDEA_SERVICES = listOf(
    "Spotify", "YouTube Music", "YouTube", "Apple Music", "Amazon Music", "TIDAL", "Deezer", "SoundCloud",
    "Subsonic/Navidrome", "Jellyfin", "Plex", "Last.fm", "ListenBrainz", "Files",
)

/** The services this build can name: the Play build has no web connectors to suggest things for. */
private fun ideaServices(): List<String> =
    if (BuildConfig.FLAVOR == "play") IDEA_SERVICES - setOf("Amazon Music", "SoundCloud") else IDEA_SERVICES

/** The version line both routes carry: which app and which build the idea comes from. */
private fun versionLine(): String = "Trackhop ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}), Android ${android.os.Build.VERSION.RELEASE}"

/**
 * The GitHub issue form (feature_request.yml) filled in from the sheet: its fields are prefilled
 * by their ids. The person reads it in the browser and posts it with their own account.
 */
internal fun ideaIssueUrl(idea: String, why: String, services: List<String>): String {
    val text = idea.trim() + if (services.isNotEmpty()) "\n\nServices: " + services.joinToString(", ") else ""
    return "https://github.com/xlollx/Trackhop/issues/new?template=feature_request.yml" +
        "&title=" + Uri.encode(idea.trim().lineSequence().first().take(70)) +
        "&idea=" + Uri.encode(text.take(3000)) +
        (if (why.isNotBlank()) "&why=" + Uri.encode(why.trim().take(2000)) else "") +
        "&version=" + Uri.encode(versionLine())
}

private fun ideaEmailBody(idea: String, why: String, services: List<String>): String = buildString {
    append(idea.trim())
    if (why.isNotBlank()) append("\n\n").append(why.trim())
    if (services.isNotEmpty()) append("\n\nServices: ").append(services.joinToString(", "))
    append("\n\n— ").append(versionLine())
}

/**
 * Settings › Help › Suggest an idea: a short form whose text goes, as the person chooses, into a
 * GitHub issue (public, others can vote) or an email (private). Nothing leaves the app by itself:
 * both open another app with the text, to read and send.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun IdeaSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var idea by rememberSaveable { mutableStateOf("") }
    var why by rememberSaveable { mutableStateOf("") }
    val services = remember { mutableStateListOf<String>() }
    var noMail by remember { mutableStateOf(false) }
    val ready = idea.isNotBlank()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.idea_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.idea_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = idea, onValueChange = { idea = it }, minLines = 3, maxLines = 8,
                label = { Text(stringResource(R.string.idea_field)) }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = why, onValueChange = { why = it }, minLines = 2, maxLines = 6,
                label = { Text(stringResource(R.string.idea_why)) }, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.idea_services), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ideaServices().forEach { s ->
                    val on = s in services
                    FilterChip(selected = on, onClick = { if (on) services.remove(s) else services.add(s) }, label = { Text(s) })
                }
            }
            Text(stringResource(R.string.idea_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (noMail) Text(stringResource(R.string.idea_no_email, IDEAS_EMAIL), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onClick = { openUrl(ctx, ideaIssueUrl(idea, why, services.toList())) },
                ) { Text(stringResource(R.string.idea_github)) }
                OutlinedButton(
                    enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onClick = {
                        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(IDEAS_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, "Trackhop idea: " + idea.trim().lineSequence().first().take(60))
                            putExtra(Intent.EXTRA_TEXT, ideaEmailBody(idea, why, services.toList()))
                        }
                        noMail = runCatching { ctx.startActivity(mail) }.isFailure
                    },
                ) { Text(stringResource(R.string.idea_email)) }
            }
            TextButton(onClick = { openUrl(ctx, IDEAS_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.idea_others))
            }
        }
    }
}
