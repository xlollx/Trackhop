package com.xlollx.songport.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.NtfyServer

/** The ntfy server a new group's channel starts on: the one ntfy notifications start on. */
val DEFAULT_NTFY_SERVER: String = Notify.Kind.NTFY.fields.first { it.key == "server" }.default

/**
 * How a new group meets, as cards: the shared folder, an encrypted ntfy channel on [server], or (when
 * "Coordinate with my server" is set, [myServer] its name) a mailbox on the user's own Trackhop server.
 * The chosen card opens on what that way needs; [folderInputs] is the folder's.
 */
@Composable
fun TransportChoice(
    transport: String, onTransport: (String) -> Unit, server: String, onServer: (String) -> Unit, myServer: String?, signIn: NtfySignIn,
    folderInputs: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shared_via), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportCard(transport == Invite.FOLDER, Invite.FOLDER, stringResource(R.string.shared_folder), stringResource(R.string.shared_via_folder_short), { onTransport(Invite.FOLDER) }) {
                folderInputs()
            }
            TransportCard(transport == Invite.NTFY, Invite.NTFY, stringResource(R.string.shared_via_ntfy), stringResource(R.string.shared_via_ntfy_short), { onTransport(Invite.NTFY) }) {
                val bad = server.isNotBlank() && !NtfyServer.valid(server)
                OutlinedTextField(
                    server, { onServer(it.trim().take(200)) }, label = { Text(stringResource(R.string.shared_ntfy_server)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), isError = bad,
                    supportingText = if (bad) { { Text(stringResource(R.string.shared_ntfy_server_invalid)) } } else null,
                )
                NtfySignInFields(signIn)
                InviteSecretNote()
            }
            if (myServer != null) {
                TransportCard(transport == Invite.SERVER, Invite.SERVER, stringResource(R.string.shared_via_server), stringResource(R.string.shared_via_server_short, myServer), { onTransport(Invite.SERVER) }) {
                    Text(stringResource(R.string.shared_via_server_desc, myServer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InviteSecretNote()
                }
            }
        }
    }
}

/** The glyph of a way to meet: a folder, the lock of an encrypted channel, the cloud of a Trackhop server. */
fun transportIcon(transport: String): ImageVector = when (transport) {
    Invite.NTFY -> Icons.Filled.Lock
    Invite.SERVER -> Icons.Filled.Cloud
    else -> Icons.Filled.Folder
}

/** The name of a way to meet, as a member who did not choose it reads it. */
@Composable
fun transportLabel(transport: String): String = stringResource(
    when (transport) {
        Invite.NTFY -> R.string.shared_via_ntfy
        Invite.SERVER -> R.string.shared_server_label
        else -> R.string.shared_folder
    },
)

/** One way to meet, as a selectable card (the service tiles' look); chosen, it opens on its [content]. */
@Composable
private fun TransportCard(selected: Boolean, transport: String, title: String, desc: String, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(transportIcon(transport), null, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RadioButton(selected = selected, onClick = null)
            }
            AnimatedVisibility(selected) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

/**
 * The group's code, from the creator's key, in four groups of five in a fixed-width face: what the
 * person joining compares with the code on the inviter's group page. The groups wrap whole on a
 * narrow screen or with large text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupCode(code: String, modifier: Modifier = Modifier) {
    val said = stringResource(R.string.shared_code, code)
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = said }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.shared_code_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (part in code.split('-')) Text(
                part, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * What an invite link says, before joining: the group, the way it meets (and where), the code to
 * compare and, for a channel or a server mailbox, that the link itself is the key.
 */
@Composable
fun InviteSummary(invite: Invite) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrefIcon(transportIcon(invite.transport))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(invite.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val where = if (invite.transport == Invite.FOLDER) invite.folderName else NtfyServer.hostOf(invite.server)
                    Text(
                        listOf(transportLabel(invite.transport), where).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A link changed on its way would make its maker the group's creator here: the code says whose group it is.
            GroupCode(invite.code)
            Text(stringResource(R.string.shared_code_check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (invite.transport != Invite.FOLDER) InviteSecretNote(stringResource(R.string.shared_join_secret))
        }
    }
}

/** The shared folder this phone reads and writes the group in: the one chosen, and the button to choose it. */
@Composable
fun FolderPicker(name: String, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (name.isBlank()) Icons.Filled.Folder else Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                name.ifBlank { stringResource(R.string.shared_folder_none) }, style = MaterialTheme.typography.bodyMedium,
                color = if (name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.shared_folder_pick))
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

/**
 * This member's own sign-in to a protected ntfy server, as typed: an access token, or a user name
 * and password. Kept only in memory while the form shows (never in saved state), then in the vault.
 */
@Stable
class NtfySignIn(on: Boolean = false) {
    var on by mutableStateOf(on)
    var token by mutableStateOf("")
    var user by mutableStateOf("")
    var password by mutableStateOf("")

    /** The Authorization header to keep, or null (none asked, or not filled in yet). */
    val auth: String? get() = if (on) NtfyServer.authorization(token, user, password) else null

    /** Nothing asked, or enough given. */
    val ready: Boolean get() = !on || auth != null

    /** Something typed in it (asked before it is dropped). */
    val typed: Boolean get() = token.isNotBlank() || user.isNotBlank() || password.isNotBlank()
}

/** "This server needs sign-in", and when ticked an access token or a user name and password. */
@Composable
fun NtfySignInFields(s: NtfySignIn) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(s.on, role = Role.Checkbox) { s.on = it }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(s.on, null)
            Text(stringResource(R.string.shared_ntfy_signin), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
        }
        if (s.on) {
            Text(stringResource(R.string.shared_ntfy_signin_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                s.token, { s.token = it.trim().take(512) }, label = { Text(stringResource(R.string.shared_ntfy_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (s.token.isBlank()) {
                OutlinedTextField(s.user, { s.user = it.take(200) }, label = { Text(stringResource(R.string.login_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    s.password, { s.password = it.take(200) }, label = { Text(stringResource(R.string.login_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
