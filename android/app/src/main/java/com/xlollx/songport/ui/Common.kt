package com.xlollx.songport.ui

import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Upgrade
import com.xlollx.songport.providers.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.heading
import com.xlollx.songport.R
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------------------------
// Servizi
// ---------------------------------------------------------------------------------------------

@Composable
fun ProviderLabel(providerId: String, detail: String? = null, modifier: Modifier = Modifier) {
    val p = Providers.byId(providerId)
    val ctx = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ProviderBadge(p, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = buildString {
                append(p?.label(ctx) ?: providerId)
                if (!detail.isNullOrBlank()) append(" · ").append(detail)
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The small service mark in a chip: colour and initial, never the colour alone, and it tells
 * TalkBack which service it is. The chip's label carries the account name next to it.
 */
@Composable
fun ProviderDot(p: MusicProvider?, sizeDp: Int = 18) = ProviderBadge(p, sizeDp.dp)

/** The service's mark ([ServiceLogo]): its glyph on its colour, read by TalkBack as its name. */
@Composable
fun ProviderBadge(p: MusicProvider?, size: Dp = 36.dp) = ServiceLogo(p, size)

// ---------------------------------------------------------------------------------------------
// Blocchi di interfaccia condivisi
// ---------------------------------------------------------------------------------------------

/**
 * Runs [block] on the main thread. Compose state and snackbars are touched only there: after an IO
 * hop a coroutine can resume on the worker thread (the UI tests' effect dispatcher does), and a
 * state write from there may never reach the screen.
 */
suspend fun <T> onMain(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate, block)

/** Warning: nothing failed, but something waits for the user (held-back removals, tracks not found). */
enum class Tone { Ok, Error, Neutral, Accent, Warning }

/** Etichetta di stato: colore + icona + testo, mai solo il colore. */
@Composable
fun StatusPill(text: String, tone: Tone, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val bg = when (tone) {
        Tone.Ok -> Tones.successContainer()
        Tone.Error -> MaterialTheme.colorScheme.errorContainer
        Tone.Accent -> MaterialTheme.colorScheme.primaryContainer
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
        Tone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val fg = when (tone) {
        Tone.Ok -> Tones.onSuccessContainer()
        Tone.Error -> MaterialTheme.colorScheme.onErrorContainer
        Tone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Schermata vuota: un'icona, una frase, al massimo un pulsante. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    ctaLabel: String? = null,
    onCta: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (ctaLabel != null && onCta != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onCta) { Text(ctaLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(if (ctaLabel != null) 4.dp else 20.dp))
            androidx.compose.material3.TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp)) { Text(secondaryLabel) }
        }
    }
}

/**
 * Scheda con titolo opzionale: raggruppa le impostazioni in blocchi leggibili. The title is text,
 * not an action, so it is in the text colour: the accent is kept for what can be tapped.
 */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/**
 * A settings row that is one big switch: title, note and the control, with the whole row as the
 * touch target (56dp at least) and read out by TalkBack as a single switch.
 */
@Composable
fun SwitchRow(
    title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, help: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) { v ->
                haptics.performHapticFeedback(if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(v)
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // A "?" sits between the words and the switch: its own target, outside the toggle.
        if (help != null) help() else Spacer(Modifier.width(16.dp))
        // The row owns the toggle; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A settings row with a control on the right (button, value...). When [onClick] is given the whole
 * row is the target, as in Android's own settings; otherwise the control is.
 */
@Composable
fun SettingRow(title: String, desc: String, onClick: (() -> Unit)? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

/** A light tap you can feel, for the actions that start or save something. */
@Composable
fun rememberConfirmHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
}

class MenuAction(
    val label: String, val onClick: () -> Unit, val destructive: Boolean = false, val enabled: Boolean = true,
    /** A leading icon; decorative, the label names the action. */
    val icon: ImageVector? = null,
)

/**
 * Le azioni secondarie stanno dietro un solo pulsante "Altro": la scheda resta pulita.
 *
 * The menu is a rounded sheet with an icon before each entry; the destructive ones (delete,
 * remove) come last, in the error colour, below a divider. [container] tints it (a sync card
 * passes its colour, see [jobMenuContainer]), [iconTint] colours the icons, and [title] puts a
 * small header on top that says what the menu is about. Plain colours only: no blend mode.
 */
@Composable
fun OverflowMenu(
    actions: List<MenuAction>, enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    title: String? = null,
) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions))
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = container,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(min = 200.dp),
        ) {
            if (title != null) {
                Text(
                    title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp).semantics { heading() },
                )
            }
            val (plain, destructive) = actions.partition { !it.destructive }
            plain.forEach { a -> OverflowItem(a, iconTint) { open = false; a.onClick() } }
            if (destructive.isNotEmpty()) {
                if (plain.isNotEmpty() || title != null) HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                destructive.forEach { a -> OverflowItem(a, iconTint) { open = false; a.onClick() } }
            }
        }
    }
}

@Composable
private fun OverflowItem(a: MenuAction, iconTint: Color, onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    DropdownMenuItem(
        text = { Text(a.label, style = MaterialTheme.typography.bodyLarge, color = if (a.destructive) error else Color.Unspecified) },
        leadingIcon = a.icon?.let { icon -> { Icon(icon, contentDescription = null, tint = if (a.destructive) error else iconTint) } },
        enabled = a.enabled,
        onClick = onClick,
        // Rounded highlight inside the rounded sheet; 48 dp tall at least, as every touch target.
        modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

/** Il marchio Trackhop (nota nell'anello), tinto con il colore che serve. */
@Composable
fun BrandMark(size: Dp = 28.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(painterResource(R.drawable.ic_brand_mark), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/**
 * The mark in white on the glass tile of the icon and the store graphics: white at 18 % with a
 * faint lighter edge, corners at 0.24 of the side, the mark filling two thirds of it. For the
 * brand surfaces ([HeroHeader], onboarding, the stats card), on the beam.
 */
@Composable
fun BrandTile(size: Dp = 40.dp) {
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        Modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.18f)).border(1.dp, Color.White.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        BrandMark(size * 0.8f, tint = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, showBrand: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBrand) {
                    BrandMark(26.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/**
 * Intestazione sul fascio del brand: titolo, sottotitolo e una riga di azioni. The beam
 * ([brandBeam]) is the brand's whatever the colour scheme (Material You too): it is the one place
 * the identity lives. The band crosses the top-right corner; the text, white at full alpha, sits
 * on the dark side, where it reads above 7:1.
 */
@Composable
fun HeroHeader(title: String, subtitle: String, link: String? = null, onLink: (() -> Unit)? = null, content: (@Composable RowScope.() -> Unit)? = null) {
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .brandBeam()
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandTile(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            // A quiet way on from the subtitle ("Your numbers ›"): a text link, not one more button.
            if (link != null && onLink != null) {
                Row(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onLink).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(link, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            if (content != null) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Testi
// ---------------------------------------------------------------------------------------------

@Composable
fun scheduleLabel(s: Schedule): String = stringResource(
    when (s) {
        Schedule.MANUAL -> R.string.schedule_manual
        Schedule.HOURLY -> R.string.schedule_hourly
        Schedule.EVERY_6H -> R.string.schedule_6h
        Schedule.DAILY -> R.string.schedule_daily
        Schedule.WEEKLY -> R.string.schedule_weekly
    }
)

/** A step of the timing slider in words: "Manual only", "Every 3 hours", "Daily", "Every 2 days", "Weekly". */
@Composable
fun stepLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.schedule_manual)
    minutes == 60 -> stringResource(R.string.schedule_hourly)
    minutes < 1440 -> stringResource(R.string.timing_every_hours, minutes / 60)
    minutes == 1440 -> stringResource(R.string.schedule_daily)
    minutes < 10080 -> stringResource(R.string.timing_every_days, minutes / 1440)
    else -> stringResource(R.string.schedule_weekly)
}

private fun dayNames(days: List<Int>): String = days.joinToString(", ") {
    java.time.DayOfWeek.of(it.coerceIn(1, 7)).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
}

/** A sync's timing in words, for its card: "Every 3 hours", "Daily at 07:30", "Mon, Wed, Fri at 08:00". */
@Composable
fun timingLabel(job: com.xlollx.songport.model.SyncJob): String {
    val T = com.xlollx.songport.sync.Timing
    if (job.days.isNotEmpty()) return stringResource(R.string.timing_at, dayNames(job.days), job.at ?: "09:00")
    val step = stepLabel(T.interval(job))
    return if (T.timed(job) && job.at != null) stringResource(R.string.timing_at, step, job.at!!) else step
}

/**
 * How often a sync runs: a slider that stops at manual, 1–23 hours, 1–6 days and a week, its value
 * read out above it; from a day up, the time of day; and, optionally, the days of the week.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingPicker(every: Int, at: String, daysCsv: String, onEvery: (Int) -> Unit, onAt: (String) -> Unit, onDays: (String) -> Unit) {
    val steps = com.xlollx.songport.sync.Timing.STEPS
    val index = steps.indexOf(every).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= every }.coerceAtLeast(0)
    val days = daysCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The usual choices one tap away; the slider in between for the rest.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 60, 360, 1440, 10080).forEach { m ->
                FilterChip(selected = every == m && days.isEmpty(), onClick = { onDays(""); onEvery(m) }, label = { Text(stepLabel(m)) })
            }
        }
        Text(if (days.isNotEmpty()) stringResource(R.string.timing_at, dayNames(days), at) else stepLabel(every),
            style = MaterialTheme.typography.titleMedium)
        Slider(
            value = index.toFloat(),
            onValueChange = { onEvery(steps[it.toInt().coerceIn(0, steps.size - 1)]) },
            valueRange = 0f..(steps.size - 1).toFloat(),
            steps = steps.size - 2,
            enabled = days.isEmpty(),
        )
        if (every >= 1440 || days.isNotEmpty()) {
            OutlinedButton(onClick = {
                val t = runCatching { java.time.LocalTime.parse(at) }.getOrDefault(java.time.LocalTime.of(9, 0))
                android.app.TimePickerDialog(ctx, { _, h, m -> onAt("%02d:%02d".format(h, m)) }, t.hour, t.minute,
                    android.text.format.DateFormat.is24HourFormat(ctx)).show()
            }) { Text(stringResource(R.string.timing_at_label, at)) }
        }
        if (every > 0 || days.isNotEmpty()) {
            Text(stringResource(R.string.timing_days_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { d ->
                    val on = d in days
                    FilterChip(selected = on, onClick = {
                        val next = if (on) days - d else (days + d).sorted()
                        onDays(next.joinToString(","))
                    }, label = { Text(dayNames(listOf(d))) })
                }
            }
        }
    }
}

fun formatDate(epoch: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))

@Composable
fun playlistDisplayName(playlistId: String?, name: String, provider: String? = null): String = when (playlistId) {
    // The heart marks the service's own default list, the one every app has; Last.fm calls it "Loved tracks".
    MusicProvider.LIKED_ID -> "\u2665 " + stringResource(if (provider == com.xlollx.songport.providers.LastFmProvider.SERVICE) R.string.lastfm_loved else R.string.liked_songs)
    MusicProvider.ALBUMS_ID -> stringResource(R.string.saved_albums)
    MusicProvider.ARTISTS_ID -> stringResource(R.string.followed_artists)
    MusicProvider.PODCASTS_ID -> stringResource(R.string.followed_podcasts)
    MusicProvider.RECENT_ID -> stringResource(R.string.recently_played)
    else -> name
}

/**
 * The top bar's arrow when a newer Trackhop exists (update/AppUpdates.kt, one per build): GitHub's
 * latest release for the GitHub build, Google Play's for the Play build. A tap gets it from where
 * this build came from: the APK from GitHub, or Trackhop's page in the Play Store. Checked when the
 * bar shows and each time the app comes back (the GitHub check itself goes out at most twice a day).
 */
@Composable
fun UpdatePill() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val available by com.xlollx.songport.update.UpdateState.available.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val check = scope.launch { com.xlollx.songport.update.AppUpdates.check(ctx) }
        onPauseOrDispose { check.cancel() }
    }
    if (available == null) return
    val label = androidx.compose.ui.res.stringResource(com.xlollx.songport.R.string.update_pill)
    val desc = androidx.compose.ui.res.stringResource(com.xlollx.songport.R.string.update_pill_desc)
    Box(
        Modifier.padding(end = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp))
            .clickable(onClickLabel = desc) { com.xlollx.songport.update.AppUpdates.open(ctx) }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(androidx.compose.material.icons.Icons.Filled.Upgrade, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/**
 * A small warm "☕ Ko-fi" pill for the Syncs header, as on the site: visible without shouting. Only
 * in the GitHub/F-Droid build: the Play build never points to payments outside Google Play.
 */
@Composable
fun CoffeePill() {
    if (com.xlollx.songport.ads.Ads.enabled || com.xlollx.songport.BuildConfig.KOFI_URL.isBlank()) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dark = LocalAppDark.current
    val label = androidx.compose.ui.res.stringResource(com.xlollx.songport.R.string.support_button)
    Box(
        Modifier.padding(end = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp))
            .clickable(onClickLabel = label) { openSupport(ctx) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "☕ Ko-fi",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (dark) Color(0xFFFFDF9E) else Color(0xFF4A2C00),
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (dark) Color(0xFF4A3410) else Color(0xFFFFE2A8)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
