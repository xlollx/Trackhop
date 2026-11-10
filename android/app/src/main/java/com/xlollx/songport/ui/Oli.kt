package com.xlollx.songport.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xlollx.songport.R
import com.xlollx.songport.net.str
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Oli, the developer's cat and Trackhop's Chief Listening Officer: a long press on the logo in
 * Settings › About (the seven taps on the version stay for the developer tools), and the Roast page
 * when there is no AI.
 */

/**
 * A purr on the vibrator: short soft pulses in pairs, like breathing in and out. Returns how long it
 * lasts, or 0 when the phone has no vibrator to use (the dialog then falls back on touch feedback).
 */
private fun purr(ctx: android.content.Context): Long {
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
    }
    if (vibrator == null || !vibrator.hasVibrator()) return 0L
    val timings = LongArray(24) { i -> longArrayOf(35, 45, 35, 170)[i % 4] }
    val amplitudes = IntArray(24) { i -> intArrayOf(90, 0, 60, 0)[i % 4] }
    val effect = if (vibrator.hasAmplitudeControl()) android.os.VibrationEffect.createWaveform(timings, amplitudes, -1)
    else android.os.VibrationEffect.createWaveform(timings, -1)
    return runCatching { vibrator.vibrate(effect); timings.sum() }.getOrDefault(0L)
}

/** Oli in full, with the title; a tap on the photo purrs. */
@Composable
internal fun OliDialog(onDismiss: () -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var purring by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(R.drawable.oli), stringResource(R.string.oli_photo),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable {
                        if (purring) return@clickable
                        purring = true
                        val ms = purr(view.context)
                        scope.launch {
                            if (ms == 0L) {
                                // No vibrator to drive: the system's touch feedback, if it is on.
                                repeat(12) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    delay(if (it % 2 == 0) 70 else 180)
                                }
                            } else delay(ms)
                            purring = false
                        }
                    },
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(Modifier.height(14.dp))
                Text("Oli", style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.oli_title), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(if (purring) R.string.oli_purring else R.string.oli_pet),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(android.R.string.ok)) }
            }
        }
    }
}

/**
 * The Roast page's photos, from the site so they can change without an app update: the list is
 * docs/assets/oli/roast.json (`{"photos": ["roast-1.jpg", …]}`), and each opening shows the next one
 * in turn. List and photos are kept in the cache; the list is asked again at most once a day. Without
 * network, or with a file that fails, the photo in the app. Anonymous requests for pictures, nothing sent.
 */
private object OliPhoto {
    private const val BASE = "https://trackhop.org/assets/oli/"
    private const val MAX_BYTES = 3_000_000
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private val NAME = Regex("[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)")

    private fun dir(ctx: android.content.Context) = java.io.File(ctx.cacheDir, "oli").apply { mkdirs() }
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("oli", android.content.Context.MODE_PRIVATE)

    private fun fetch(url: String): ByteArray? {
        com.xlollx.songport.net.HostLog.record("trackhop.org")
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return try {
            if (c.responseCode != 200) null
            else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    /** One photo of the list and its line in the phone's language (null: the app's own line). */
    class Shot(val file: String, val line: String?)

    /** The site's list, from the cache or (at most once a day) from the site. */
    private fun list(ctx: android.content.Context): List<Shot> {
        val f = java.io.File(dir(ctx), "roast.json")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > DAY_MS) {
            runCatching { fetch(BASE + "roast.json") }.getOrNull()?.let { f.writeBytes(it) }
                ?: run { if (f.exists()) f.setLastModified(System.currentTimeMillis()) }
        }
        val lang = java.util.Locale.getDefault().language
        return runCatching {
            com.xlollx.songport.net.parseJson(f.readText())["photos"].arr.mapNotNull { e ->
                // {"file": "…", "text": {"en": "…", "it": "…"}}, or just "file.jpg".
                val file = e.str ?: e["file"].str ?: return@mapNotNull null
                if (!NAME.matches(file)) return@mapNotNull null
                val text = e["text"]
                Shot(file, text[lang].str ?: text["en"].str)
            }
        }.getOrDefault(emptyList())
    }

    /** The next photo in turn with its line, or null for the app's own. Runs on a background thread. */
    fun next(ctx: android.content.Context): Pair<android.graphics.Bitmap, String?>? = runCatching {
        val photos = list(ctx)
        if (photos.isEmpty()) return null
        val p = prefs(ctx)
        val i = p.getInt("next", 0)
        p.edit().putInt("next", (i + 1) % photos.size).apply()
        val shot = photos[i % photos.size]
        val f = java.io.File(dir(ctx), shot.file)
        if (!f.exists()) fetch(BASE + shot.file)?.takeIf { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) != null }?.let { f.writeBytes(it) }
        val bmp = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path) else null
        bmp?.let { it to shot.line }
    }.getOrNull()
}

/** The Roast with no AI at all: Oli's verdict, a look, and the way to an AI for one in words. */
@Composable
internal fun OliRoast(onSetUpAi: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var photo by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { OliPhoto.next(ctx) }
        photo = next?.first?.asImageBitmap(); line = next?.second
        loaded = true
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A blank circle for the moment the next photo is read, so the app's photo does not flash first.
            val shot = photo
            when {
                shot != null -> Image(shot, stringResource(R.string.oli_photo), Modifier.size(200.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                loaded -> Image(painterResource(R.drawable.oli_roast), stringResource(R.string.oli_photo), Modifier.size(200.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                else -> androidx.compose.foundation.layout.Box(Modifier.size(200.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
            }
            if (loaded) Text(line ?: stringResource(R.string.oli_roast_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.oli_roast_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Button(onClick = onSetUpAi) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.oli_roast_set_up))
            }
        }
    }
}
