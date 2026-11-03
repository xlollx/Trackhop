package com.xlollx.songport.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Settings
import kotlinx.coroutines.launch

/**
 * "What's new": a few highlights of each significant release, written for people rather than the
 * full changelog (that is on GitHub, behind "All changes"). A release is significant when it has an
 * entry in [WhatsNew.releases]: only feature releases get one, so a bug-fix update shows nothing.
 *
 * The sheet opens by itself once, on the first start after updating to a release with an entry
 * (never on a fresh install: finishing the welcome marks the newest one as seen), and on demand
 * from Settings › About. However it is closed, the newest release counts as seen.
 */
object WhatsNew {
    /** One release's highlights. [version] is the release's "major.minor", as "1.4". */
    data class Release(val version: String, val items: List<Item>)

    /**
     * One highlight: an icon on a tile in [color], a short title and a sentence. [tint], when set,
     * colours the icon instead of [color] (a brand's own mark, as Shazam's blue).
     */
    data class Item(
        val icon: ImageVector,
        val color: JobColor,
        @StringRes val title: Int,
        @StringRes val body: Int,
        val tint: (@Composable () -> Color)? = null,
    )

    /** At most this many releases at once, for someone who skipped several. */
    const val MAX_SHOWN = 3

    /**
     * Newest first. Shown to the Google Play build too: nothing here about plugins, payments or a
     * service that build does not have.
     */
    val releases: List<Release> by lazy {
        listOf(
            Release(
                "1.7",
                listOfNotNull(
                    Item(Icons.Filled.Checklist, JobColor.BLUE, R.string.whats_new_17_review_title, R.string.whats_new_17_review_body),
                    // Only where TikTok's page is read: the Play build does not.
                    Item(Icons.Filled.MusicVideo, JobColor.PINK, R.string.whats_new_17_tiktok_title, R.string.whats_new_17_tiktok_body)
                        .takeIf { com.xlollx.songport.sync.TikTokShare.enabled },
                ),
            ),
            Release(
                "1.6",
                listOf(
                    Item(Icons.AutoMirrored.Filled.ManageSearch, JobColor.BLUE, R.string.whats_new_16_match_title, R.string.whats_new_16_match_body),
                    Item(Icons.Filled.Dns, JobColor.GREEN, R.string.whats_new_16_servers_title, R.string.whats_new_16_servers_body),
                    Item(Icons.Filled.Lightbulb, JobColor.ORANGE, R.string.whats_new_16_idea_title, R.string.whats_new_16_idea_body),
                ),
            ),
            Release(
                "1.5",
                listOf(
                    Item(Icons.Filled.Radio, JobColor.PINK, R.string.whats_new_15_radio_title, R.string.whats_new_15_radio_body),
                    Item(Icons.Filled.Wifi, JobColor.GREEN, R.string.whats_new_15_light_title, R.string.whats_new_15_light_body),
                    Item(Icons.Filled.Speed, JobColor.BLUE, R.string.whats_new_15_parallel_title, R.string.whats_new_15_parallel_body),
                ),
            ),
            Release(
                "1.4",
                listOf(
                    Item(ShazamGlyph, JobColor.BLUE, R.string.whats_new_14_shazam_title, R.string.whats_new_14_shazam_body, tint = { shazamTint() }),
                    Item(Icons.Filled.SettingsBackupRestore, JobColor.TEAL, R.string.whats_new_14_backup_title, R.string.whats_new_14_backup_body),
                    Item(Icons.Filled.Palette, JobColor.PURPLE, R.string.whats_new_14_look_title, R.string.whats_new_14_look_body),
                    Item(Icons.AutoMirrored.Filled.Chat, JobColor.ORANGE, R.string.whats_new_14_messages_title, R.string.whats_new_14_messages_body),
                ),
            ),
        )
    }

    /** The newest release with an entry: what closing the sheet, or finishing the welcome, marks as seen. */
    val newestVersion: String get() = releases.first().version

    /** The releases to show by themselves at this start, from the settings; empty for none. */
    fun due(settings: Settings): List<Release> {
        val versions = versionsToShow(releases.map { it.version }, settings.whatsNewSeen, settings.onboardingDone)
        return releases.filter { it.version in versions }
    }

    /**
     * Which of [newestFirst] to show, newest first, at most [max]:
     * - the welcome not done yet ([onboardingDone] false) is a fresh install: none, the welcome
     *   marks the newest as seen when it ends;
     * - nothing [seen] yet with the welcome done is an update from a version before this sheet: all;
     * - otherwise the ones newer than [seen].
     */
    fun versionsToShow(newestFirst: List<String>, seen: String, onboardingDone: Boolean, max: Int = MAX_SHOWN): List<String> {
        if (!onboardingDone) return emptyList()
        return newestFirst.filter { seen.isBlank() || compareVersions(it, seen) > 0 }.take(max)
    }

    /** "1.4" against "1.3.10", part by part as numbers; a missing part counts as 0, a blank version is the oldest. */
    fun compareVersions(a: String, b: String): Int {
        if (a.isBlank() || b.isBlank()) return a.isNotBlank().compareTo(b.isNotBlank())
        val x = a.trim().split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.trim().split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    const val ALL_CHANGES_URL = "$SOURCE_URL/releases"
}

/**
 * The sheet: the brand's beam with the title, one row per highlight, "Got it" and a link to the
 * full release notes. Scrolls when the text is large. [onClose] runs however it is closed (the
 * button, a swipe down, back, a tap outside), after the sheet has slid away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(releases: List<WhatsNew.Release>, onClose: () -> Unit) {
    if (releases.isEmpty()) return
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WhatsNewHeader(releases.first().version)
            releases.forEach { r ->
                // Someone who skipped releases sees each one under its name.
                if (releases.size > 1) Text(
                    stringResource(R.string.whats_new_release, r.version),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
                )
                r.items.forEach { WhatsNewRow(it) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Button(
                    onClick = { scope.launch { state.hide() }.invokeOnCompletion { onClose() } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.whats_new_got_it)) }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { openUrl(ctx, WhatsNew.ALL_CHANGES_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.whats_new_all_changes))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

/** A compact [HeroHeader]: the mark's tile, the title (a heading for TalkBack) and one line under it. */
@Composable
private fun WhatsNewHeader(version: String) {
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).brandBeam(edgeY = 0.3f).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandTile(44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.whats_new_title, version),
                    color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.whats_new_subtitle), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** One highlight: its icon on a tile tinted in its colour, the title and the sentence. The icon is decoration. */
@Composable
private fun WhatsNewRow(item: WhatsNew.Item) {
    val dark = LocalAppDark.current
    val accent = if (dark) item.color.dark else item.color.light
    val tile = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, accent, if (dark) 0.24f else 0.16f)
    val ink = item.tint?.invoke() ?: if (dark) accent else lerp(accent, Color.Black, 0.3f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tile), contentAlignment = Alignment.Center) {
            Icon(item.icon, null, tint = ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(item.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(item.body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
