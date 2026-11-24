package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.*
import com.xlollx.songport.providers.ServiceCatalog.Category
import com.xlollx.songport.providers.ServiceCatalog.Role

/** The tag of the chooser's search field, for the UI tests. */
const val CHOOSER_SEARCH_TAG = "chooser-search"

/** A category's name, as on its chip and over its block. */
internal fun categoryName(c: Category): Int = when (c) {
    Category.STREAMING -> R.string.services_group_streaming
    Category.SERVER -> R.string.services_group_servers
    Category.GAMES -> R.string.chooser_cat_games
    Category.RADIO -> R.string.chooser_cat_radio
    Category.PODCASTS -> R.string.chooser_cat_podcasts
    Category.LISTENING -> R.string.chooser_cat_listening
    Category.REGIONAL -> R.string.chooser_cat_regional
    Category.FILES -> R.string.chooser_cat_files
}

private fun areaName(a: ServiceCatalog.Area): Int = when (a) {
    ServiceCatalog.Area.EAST_ASIA -> R.string.chooser_area_east_asia
    ServiceCatalog.Area.SOUTH_ASIA_MIDDLE_EAST -> R.string.chooser_area_india_middle_east
    ServiceCatalog.Area.AFRICA -> R.string.chooser_area_africa
    ServiceCatalog.Area.EUROPE -> R.string.chooser_area_europe
    ServiceCatalog.Area.AMERICAS -> R.string.chooser_area_americas
}

/** The phone's country (the network's, else the language setting's) and language: where regional services are suggested. */
internal fun phoneCountry(ctx: android.content.Context): String? =
    runCatching { ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }?.uppercase()
        ?: ctx.resources.configuration.locales[0]?.country?.takeIf { it.isNotBlank() }

internal fun phoneLanguage(ctx: android.content.Context): String? = ctx.resources.configuration.locales[0]?.language

/**
 * "Which service?": the same chooser in Accounts › Add connector ([Role.ADD]) and in the sync editor
 * ([Role.SOURCE], [Role.TARGET]). A search box, the accounts already connected (with their colour), the
 * category chips (a chip with nothing in it is not shown), then each category's first rows and "Show all".
 * A service that cannot play the role asked stays visible, greyed, with the reason. Only the services
 * this build may offer are listed ([addableServices]): with a plugin, its services join their category.
 *
 * [accounts]: the "Your accounts" strip; tapping one hands it to [onAccount] (the editor selects it), or,
 * without one, picks its service like its row. [onPick] gets the way in (the route) to add.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ServiceChooserDialog(
    role: Role,
    connectors: List<String>,
    accounts: List<MusicProvider>,
    onDismiss: () -> Unit,
    onPick: (MusicProvider) -> Unit,
    onAccount: ((MusicProvider) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val families = remember { ServiceCatalog.families(addableServices(ctx)) }
    val country = remember { phoneCountry(ctx) }
    val language = remember { phoneLanguage(ctx) }
    // Kept across a rotation: what is typed, the chip, the service whose routes are open.
    var query by rememberSaveable { mutableStateOf("") }
    var chipKey by rememberSaveable { mutableStateOf<String?>(null) }
    var familyName by rememberSaveable { mutableStateOf<String?>(null) }
    val chip = Category.of(chipKey)
    val chosen = familyName?.let { n -> families.firstOrNull { it.name == n } }

    fun taken(m: MusicProvider) = !m.supportsMultipleAccounts && connectors.any { Providers.byId(it)?.serviceId == m.serviceId }
    // A web connector that would take another account but cannot here opens its route, where the line under it says why.
    fun explained(f: ServiceCatalog.Family) = f.members.all(::taken) && f.members.any { WebAccounts.reason(ctx, it) != null }
    fun open(f: ServiceCatalog.Family) {
        val usable = f.members.filter { ServiceCatalog.reason(it, role) == null }
        // Deezer without its official route opens its routes too, where the line says why.
        val why = f.name == "Deezer" && deezerOfficialWithheld(ctx)
        if (f.members.size == 1 && usable.size == 1 && !explained(f) && !why) onPick(usable[0]) else familyName = f.name
    }

    FitDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.connector_pick_title))
                if (chosen == null) {
                    Spacer(Modifier.padding(top = 10.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(CHOOSER_SEARCH_TAG),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = RoundedCornerShape(28.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) ({
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.chooser_clear_search)) }
                        }) else null,
                        placeholder = { Text(pluralStringResource(R.plurals.chooser_search, families.size, families.size), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
        text = {
            Column {
                if (chosen != null) {
                    RoutesPage(chosen, role, ::taken, onBack = { familyName = null }, onPick = onPick)
                } else if (query.isNotBlank()) {
                    // Typing: results from every category at once, each saying where it belongs.
                    val hits = ServiceCatalog.search(families, query)
                    if (hits.isEmpty()) Text(
                        stringResource(R.string.chooser_no_results, query.trim()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
                    )
                    hits.forEach { f -> FamilyRow(f, role, ::taken, explained(f), searching = true) { open(f) } }
                } else {
                    if (chip == null && accounts.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.chooser_your_accounts), stringResource(R.string.connected))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            accounts.forEach { p ->
                                val reason = ServiceCatalog.reason(p, role)
                                val family = families.firstOrNull { f -> f.members.any { it.serviceId == p.serviceId } }
                                val enabled = reason == null && (onAccount != null || (family != null && (!family.members.all(::taken) || explained(family))))
                                AccountTile(p, enabled) { if (onAccount != null) onAccount(p) else family?.let(::open) }
                            }
                        }
                    }
                    val cats = ServiceCatalog.categories(families)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = chip == null, onClick = { chipKey = null }, label = { Text(stringResource(R.string.chooser_all)) })
                        cats.forEach { c -> FilterChip(selected = chip == c, onClick = { chipKey = c.key }, label = { Text(stringResource(categoryName(c))) }) }
                    }
                    ServiceCatalog.sections(families, chip, country, language).forEach { s ->
                        val title = when {
                            s.suggested -> stringResource(R.string.chooser_suggested_here)
                            s.area != null -> stringResource(areaName(s.area!!))
                            else -> stringResource(categoryName(s.category))
                        }
                        val side = when {
                            s.suggested -> country?.let { java.util.Locale("", it).displayCountry }.orEmpty()
                            s.area != null -> ""
                            else -> s.total.toString()
                        }
                        SectionHeader(title, side)
                        s.families.forEach { f -> FamilyRow(f, role, ::taken, explained(f), searching = false) { open(f) } }
                        if (chip == null && s.more) TextButton(onClick = { chipKey = s.category.key }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.chooser_show_all, s.total))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                    // No Deezer at all (Play build without the connectors): why, in a line.
                    if (chip == null && families.none { it.name == "Deezer" } && deezerOfficialWithheld(ctx)) DeezerWhyRow(official = false)
                }
            }
        },
        // No link out: what plugins are is explained, neutrally, in Settings › Plugins (Play build).
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SectionHeader(title: String, side: String) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (side.isNotEmpty()) Text(side, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An account already connected: its logo, larger, with the colour the user gave it, and its name. */
@Composable
private fun AccountTile(p: MusicProvider, enabled: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.45f).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ServiceLogo(p, 44.dp, ring = accountRing(p.id))
        Spacer(Modifier.padding(top = 6.dp))
        Text(remember(p.id) { p.label(ctx) }, style = MaterialTheme.typography.labelSmall, maxLines = 2, minLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

/** One service: logo, name, a line under it (why it is greyed, or what it is), and where it is at home. */
@Composable
private fun FamilyRow(f: ServiceCatalog.Family, role: Role, taken: (MusicProvider) -> Boolean, explained: Boolean, searching: Boolean, onClick: () -> Unit) {
    val reason = f.members.mapNotNull { ServiceCatalog.reason(it, role) }.takeIf { it.size == f.members.size }?.first()
    val allTaken = f.members.all(taken)
    val enabled = reason == null && (!allTaken || explained)
    val subtitle = when {
        reason != null -> stringResource(R.string.chooser_cannot_target)
        allTaken && !explained -> stringResource(R.string.connected)
        searching -> listOfNotNull(stringResource(categoryName(f.category)), f.area?.let { stringResource(areaName(it)) }).joinToString(" · ")
        f.aliases.isNotEmpty() && f.category == Category.SERVER -> f.aliases.take(3).joinToString(", ") + if (f.aliases.size > 3) "…" else ""
        f.members.size > 1 -> f.members.sortedBy { if (it.route == MusicProvider.Route.EASY) 0 else 1 }
            .mapNotNull { it.route }.map { stringResource(if (it == MusicProvider.Route.EASY) R.string.route_easy else R.string.route_official) }.joinToString(" · ")
        else -> listOfNotNull(
            f.lead.route?.let { stringResource(if (it == MusicProvider.Route.EASY) R.string.route_easy else R.string.route_official) },
            stringResource(R.string.read_only).takeIf { !f.lead.canWrite },
            if (f.lead.alpha) stringResource(R.string.alpha) else if (f.lead.beta) stringResource(R.string.beta) else null,
        ).joinToString(" · ")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServiceLogo(f.lead, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        f.tag?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

/** A service reachable two ways: each way on its own card, saying what it asks and what it is; the easiest first. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RoutesPage(chosen: ServiceCatalog.Family, role: Role, taken: (MusicProvider) -> Boolean, onBack: () -> Unit, onPick: (MusicProvider) -> Unit) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) }
        ServiceLogo(chosen.lead, 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(chosen.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    if (chosen.members.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.connector_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        HelpButton(Helps.routes)
    }
    chosen.members.sortedBy { if (it.route == MusicProvider.Route.EASY) 0 else 1 }.forEach { s ->
        val isTaken = taken(s)
        val reason = ServiceCatalog.reason(s, role)
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 56.dp).clip(ToolCardShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = !isTaken && reason == null) { onPick(s) }.alpha(if (!isTaken && reason == null) 1f else 0.6f)
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    s.route?.let { r ->
                        // Both routes wear the lock: the quick one signs in on the service's own page too.
                        StatusPill(stringResource(if (r == MusicProvider.Route.EASY) R.string.route_easy else R.string.route_official), if (r == MusicProvider.Route.EASY) Tone.Ok else Tone.Accent, Icons.Filled.Lock)
                    } ?: Text(s.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (!s.canWrite) Text(stringResource(R.string.read_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.alpha || s.beta) Text(stringResource(if (s.alpha) R.string.alpha else R.string.beta), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isTaken) Text(stringResource(R.string.connected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.routeNoteRes?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (reason != null) Text(stringResource(R.string.chooser_cannot_target), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                // A web connector that would take another account, but cannot here: why, in a line.
                if (isTaken) WebAccounts.reason(ctx, s)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Deezer with its web route only: why the official one is missing, where its card was.
    if (chosen.name == "Deezer" && chosen.members.none { it is com.xlollx.songport.providers.DeezerProvider } && deezerOfficialWithheld(LocalContext.current)) DeezerWhyRow(official = true)
}
