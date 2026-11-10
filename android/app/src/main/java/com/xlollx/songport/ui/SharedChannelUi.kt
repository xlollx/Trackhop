package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.NtfyServer

/** The ntfy server a new group's channel starts on: the one ntfy notifications start on. */
val DEFAULT_NTFY_SERVER: String = Notify.Kind.NTFY.fields.first { it.key == "server" }.default

/**
 * How a new group meets: the shared folder, an encrypted ntfy channel on [server], or (when
 * "Coordinate with my server" is set, [myServer] its name) a mailbox on the user's own Trackhop server.
 */
@Composable
fun TransportChoice(transport: String, onTransport: (String) -> Unit, server: String, onServer: (String) -> Unit, myServer: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.shared_via), style = MaterialTheme.typography.labelLarge)
        TransportOption(transport == Invite.FOLDER, stringResource(R.string.shared_folder), stringResource(R.string.shared_via_folder_desc)) { onTransport(Invite.FOLDER) }
        TransportOption(transport == Invite.NTFY, stringResource(R.string.shared_via_ntfy), stringResource(R.string.shared_via_ntfy_desc)) { onTransport(Invite.NTFY) }
        if (myServer != null) {
            TransportOption(transport == Invite.SERVER, stringResource(R.string.shared_via_server), stringResource(R.string.shared_via_server_desc, myServer)) { onTransport(Invite.SERVER) }
        }
        if (transport == Invite.NTFY) {
            val bad = server.isNotBlank() && !NtfyServer.valid(server)
            OutlinedTextField(
                server, { onServer(it.trim().take(200)) }, label = { Text(stringResource(R.string.shared_ntfy_server)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), isError = bad,
                supportingText = if (bad) { { Text(stringResource(R.string.shared_ntfy_server_invalid)) } } else null,
            )
        }
        if (transport != Invite.FOLDER) InviteSecretNote()
    }
}

@Composable
private fun TransportOption(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An encrypted channel's invite, or a server mailbox's, carries the group's key: said where the invite is made, shown and opened. */
@Composable
fun InviteSecretNote(text: String = stringResource(R.string.shared_invite_secret)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}
