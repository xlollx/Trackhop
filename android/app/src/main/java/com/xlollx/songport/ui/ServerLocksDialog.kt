package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.ServerLocks
import kotlinx.coroutines.launch

/**
 * Settings › Advanced › Coordinate with my server: the address of the user's Trackhop server and
 * the API token made there, kept with this phone's secrets. Test reserves a playlist nobody has and
 * gives it back; Remove turns the option off.
 */
@Composable
fun ServerLocksDialog(initial: ServerLocks.Config?, onDismiss: () -> Unit, onSaved: (ServerLocks.Config?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(initial?.address.orEmpty()) }
    // The token stays in this process only, never in saved state.
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    /** The test's answer, as said to the user; null = not tried since the last change. */
    var result by remember { mutableStateOf<String?>(null) }
    val complete = address.isNotBlank() && token.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_locks_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.help_server_locks_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = address, onValueChange = { address = it; result = null }, singleLine = true,
                    label = { Text(stringResource(R.string.server_locks_address)) }, placeholder = { Text("http://192.168.1.10:8080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it; result = null }, singleLine = true,
                    label = { Text(stringResource(R.string.server_locks_token)) }, visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text(stringResource(R.string.server_locks_token_hint)) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = complete && !testing, onClick = {
                        testing = true; result = null
                        scope.launch {
                            val answer = ServerLocks(address, token.trim(), android.os.Build.MODEL.orEmpty(), { ctx.env.log("server", it) }).check()
                            result = when (answer) {
                                ServerLocks.Check.Ok -> ctx.getString(R.string.server_locks_ok)
                                ServerLocks.Check.Old -> ctx.getString(R.string.server_locks_old)
                                ServerLocks.Check.Token -> ctx.getString(R.string.server_locks_token_wrong)
                                is ServerLocks.Check.Unreachable -> ctx.getString(R.string.server_locks_unreachable)
                                is ServerLocks.Check.Refused -> ctx.getString(R.string.server_locks_refused, answer.why)
                            }
                            testing = false
                        }
                    }) { Text(stringResource(R.string.ai_check)) }
                    if (testing) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp)) }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(enabled = complete, onClick = {
                val c = ServerLocks.Config(address, token.trim())
                ServerLocks.save(ctx.env, c)
                ctx.env.log("server", "coordinating with ${ServerLocks.normalize(address)}")
                onSaved(ServerLocks.config(ctx.env))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = {
                    ServerLocks.clear(ctx.env)
                    ctx.env.log("server", "no longer coordinating with a server")
                    onSaved(null)
                }) { Text(stringResource(R.string.remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
