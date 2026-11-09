package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R

/**
 * One "Support Trackhop" entry everywhere (Settings › About, welcome, Syncs pill, cards): it opens
 * this sheet with the two ways, Ko-fi and GitHub Sponsors. Optional and unlocks nothing.
 */
object SupportChooser {
    var open by mutableStateOf(false)
}

/** Both ways set: the sheet; only one: straight to it. */
fun openSupport(ctx: android.content.Context) {
    val kofi = BuildConfig.KOFI_URL.isNotBlank()
    val sponsors = BuildConfig.SPONSORS_URL.isNotBlank()
    when {
        kofi && sponsors -> SupportChooser.open = true
        kofi -> openUrl(ctx, BuildConfig.KOFI_URL)
        else -> openUrl(ctx, BuildConfig.SPONSORS_URL)
    }
}

/** Placed once over the whole app (MainActivity); shows nothing until [openSupport] asks for it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportChooserHost() {
    if (!SupportChooser.open) return
    val ctx = LocalContext.current
    val close = { SupportChooser.open = false }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.support_row), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.support_choose_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SupportWay("☕", null, "Ko-fi", stringResource(R.string.support_kofi_desc)) { close(); openUrl(ctx, BuildConfig.KOFI_URL) }
            SupportWay(null, Icons.Filled.Favorite, stringResource(R.string.support_sponsors), stringResource(R.string.support_sponsors_desc)) { close(); openUrl(ctx, BuildConfig.SPONSORS_URL) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text(stringResource(R.string.ai_done_close)) }
            }
        }
    }
}

@Composable
private fun SupportWay(emoji: String?, icon: ImageVector?, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (emoji != null) Text(emoji, style = MaterialTheme.typography.titleLarge)
            else if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}
