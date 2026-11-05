package com.xlollx.songport.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xlollx.songport.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Oli, the developer's cat and Trackhop's Chief Listening Officer: a long press on the logo in
 * Settings › About (the seven taps on the version stay for the developer tools), a "Supervision" row
 * at the end of that box once in a thousand openings, and the Roast page when there is no AI.
 */

/** Oli's head, round: the Supervision row's icon. */
@Composable
internal fun OliHead(size: androidx.compose.ui.unit.Dp = 40.dp) = Image(
    painterResource(R.drawable.oli_head), null,
    Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop,
)

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
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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

/** The Roast with no AI at all: Oli's verdict, a look, and the way to an AI for one in words. */
@Composable
internal fun OliRoast(onSetUpAi: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painterResource(R.drawable.oli_smirk), stringResource(R.string.oli_photo),
                Modifier.size(200.dp).clip(CircleShape), contentScale = ContentScale.Crop,
            )
            Text(stringResource(R.string.oli_roast_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
