package com.xlollx.songport.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.18: a cover made by the user's own ChatGPT or Gemini, free and with no key. Trackhop writes the
 * request (the same wordless one as the AI cover) and opens the app with it, or copies it and opens
 * the site; the user makes the picture with their own account, saves it and chooses it here. Nothing
 * is automated in those apps: their terms leave them to people, not to programs.
 */
object OwnImage {
    enum class App(val pkg: String, val label: String, val site: String) {
        CHATGPT("com.openai.chatgpt", "ChatGPT", "https://chatgpt.com/"),
        GEMINI("com.google.android.apps.bard", "Gemini", "https://gemini.google.com/app"),
    }

    /** The request as said to a chat: asked for an image, then the cover's own words. */
    fun request(coverPrompt: String): String = "Generate an image. $coverPrompt"

    /** The app with the request typed in, else its site; the request is copied either way. */
    fun ask(ctx: Context, app: App, coverPrompt: String) {
        val text = request(coverPrompt)
        runCatching {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Trackhop", text))
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(app.pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { ctx.startActivity(send) }.isSuccess ||
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.site)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        android.widget.Toast.makeText(ctx, ctx.getString(if (opened) R.string.own_image_copied else R.string.own_image_copied_only), android.widget.Toast.LENGTH_LONG).show()
    }

    /** A picture the user chose, at most [max] pixels on its longer side. */
    fun decode(ctx: Context, uri: Uri, max: Int = 1400): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > max * 2) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()
}

/** Ask ChatGPT, Ask Gemini, Choose image, and what to do in between. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwnImagePanel(coverPrompt: suspend () -> String, enabled: Boolean, onPicked: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { withContext(Dispatchers.IO) { OwnImage.decode(ctx, uri) }?.let(onPicked) }
    }
    var asking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.own_image_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OwnImage.App.entries.forEach { app ->
                // The request says what is in the playlist: read first when the poster was not made yet.
                OutlinedButton(enabled = enabled && !asking, onClick = {
                    asking = true
                    scope.launch { try { OwnImage.ask(ctx, app, coverPrompt()) } finally { asking = false } }
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.own_image_ask, app.label))
                }
            }
            Button(enabled = enabled, onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.own_image_pick))
            }
        }
    }
}
