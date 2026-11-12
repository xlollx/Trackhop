package com.xlollx.songport.ui

import androidx.compose.material.icons.filled.Key
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.heading
import com.xlollx.songport.providers.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import kotlinx.coroutines.launch

private const val PAGES = 5

/**
 * First start, in five pages: what Trackhop does (the services it joins), the three moves, the
 * Discover tab, how logins are kept and why the app is free, and where the
 * documentation and the code are. Skip goes straight to the app; the last page's button starts it.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onTour: () -> Unit = onDone) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> StepsPage()
                    2 -> DiscoverIntroPage()
                    3 -> TrustPage()
                    else -> DocsPage()
                }
            }
            // Skip, the dots and Next stay in the same place on every page.
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (!last) TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_skip)) }
                    // The end of the welcome offers the guided first sync, once; Start goes straight in.
                    else TextButton(onClick = onTour, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.tour_offer)) }
                }
                Dots(pager.currentPage)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (last) {
                        Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_start)) }
                    } else {
                        Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.onboarding_next))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dots(current: Int) {
    val label = stringResource(R.string.onboarding_page, current + 1, PAGES)
    Row(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(PAGES) { i ->
            val on = i == current
            val w by animateDpAsState(if (on) 22.dp else 8.dp, label = "dot")
            val c by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor")
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(c))
        }
    }
}

/** A page's text: a readable column in the middle of a wide screen, the full width on a phone. */
private fun ColumnScope.readable(): Modifier = Modifier.widthIn(max = WindowLayout.READABLE_CONTENT).fillMaxWidth().align(Alignment.CenterHorizontally)

/** The page's art on the brand beam, under the status bar. */
@Composable
private fun Hero(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .brandBeam(edgeY = 0.3f)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

/** Page 1: the mark among the services it joins, and what the app is for. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WelcomePage() {
    val logos = remember {
        // Amazon Music only where the build carries its connector: the Play build reaches it through
        // a separately installed plugin, so it does not show it as a service of its own.
        // The same goes for the other quick sign-in services (Qobuz, Anghami): only where built in.
        val builtInOnly = setOf("amazon", "qobuz_web", "anghami_web")
        listOf("spotify", "apple", "ytm", "amazon", "tidal", "deezer", "qobuz_web", "soundcloud", "anghami_web", "subsonic", "jellyfin", "emby", "plex")
            .filter { it !in builtInOnly || com.xlollx.songport.providers.BridgePlugin.builtIn }
            .mapNotNull { id -> Providers.services().firstOrNull { it.serviceId == id } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero {
            BrandTile(88.dp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                logos.forEach { ServiceLogo(it, 44.dp) }
            }
        }
        Column(readable().padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Page 2: connect, create, let it run, as three linked steps. */
@Composable
private fun StepsPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroIcon(Icons.Filled.AccountCircle)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.7f))
                HeroIcon(Icons.Filled.PlaylistAdd)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.7f))
                HeroIcon(Icons.Filled.Sync)
            }
        }
        Column(readable().padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Each step in the colour of where it happens: Accounts' teal, the syncs' own, then the schedule.
            StepRow(Icons.Filled.AccountCircle, stringResource(R.string.onboarding_step1_title), stringResource(R.string.onboarding_step1_desc), JobColor.TEAL)
            StepRow(Icons.Filled.PlaylistAdd, stringResource(R.string.onboarding_step2_title), stringResource(R.string.onboarding_step2_desc))
            StepRow(Icons.Filled.Schedule, stringResource(R.string.onboarding_step3_title), stringResource(R.string.onboarding_step3_desc), JobColor.BLUE)
        }
    }
}

/** Page 3: the Discover tab, the fun side: Wrapped, links for friends, how compatible you are. */
@Composable
private fun DiscoverIntroPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero { HeroIcon(Icons.Filled.AutoAwesome, 72.dp) }
        Column(readable().padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.onboarding_discover_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp).semantics { heading() })
            // Discover's pink, Tools' orange for the generator that lives there.
            StepRow(Icons.Filled.AutoAwesome, stringResource(R.string.onboarding_discover_wrapped_title), stringResource(R.string.onboarding_discover_wrapped_desc), JobColor.PINK)
            StepRow(Icons.Filled.Link, stringResource(R.string.onboarding_discover_link_title), stringResource(R.string.onboarding_discover_link_desc), JobColor.PINK)
            StepRow(Icons.Filled.QueueMusic, stringResource(R.string.onboarding_discover_mix_title), stringResource(R.string.onboarding_discover_mix_desc), JobColor.ORANGE)
        }
    }
}

/** Page 3: logins stay on the phone; why the app is free. */
@Composable
private fun TrustPage() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero { HeroIcon(Icons.Filled.Lock, 72.dp) }
        Column(readable().padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.security_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.onboarding_security), style = MaterialTheme.typography.bodyLarge)
            PageNote(stringResource(R.string.onboarding_credentials), icon = Icons.Filled.Key)
            Spacer(Modifier.height(4.dp))
            // Why it is free, on a card of its own with the way to support it.
            PageCard {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.free_why_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    // The Play build never points to payments outside Google Play: no coffee there.
                    Text(stringResource(if (com.xlollx.songport.BuildConfig.STORE_BUILD) R.string.settings_free_note else R.string.free_why_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!BuildConfig.STORE_BUILD && (BuildConfig.KOFI_URL.isNotBlank() || BuildConfig.SPONSORS_URL.isNotBlank())) {
                        TextButton(onClick = { openSupport(ctx) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Favorite, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.support_row))
                        }
                    } else Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** Page 4: where to read more: the website (guides, news) and GitHub (code, releases); both links are optional. */
@Composable
private fun DocsPage() {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero { HeroIcon(Icons.Filled.Code, 72.dp) }
        Column(readable().padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.onboarding_docs_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.onboarding_docs_body), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            LinkCard(
                // The app's own icon, as on the site, filling the disc.
                mark = { Image(androidx.compose.ui.res.painterResource(R.drawable.trackhop_icon), null, Modifier.size(44.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) },
                markColor = Color.Transparent,
                title = "trackhop.org",
                desc = stringResource(R.string.onboarding_docs_site_desc),
                onClick = { open(SITE_URL) },
            )
            // GitHub's dark disc sinks into a dark card: on a dark theme the mark goes dark on white.
            val dark = LocalAppDark.current
            LinkCard(
                mark = { Image(GitHubGlyph, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(if (dark) Color(0xFF24292F) else Color.White)) },
                markColor = if (dark) Color.White else Color(0xFF24292F),
                title = "GitHub",
                desc = stringResource(R.string.onboarding_docs_github_desc),
                onClick = { open(SOURCE_URL) },
            )
        }
    }
}

/** A link as a whole tappable card: the mark on its disc, a name and a line on what is there, and the "opens outside" arrow. */
@Composable
private fun LinkCard(mark: @Composable () -> Unit, markColor: Color, title: String, desc: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ToolCardShape,
        color = pageCardColor(),
        shadowElevation = if (LocalAppDark.current) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(markColor), contentAlignment = Alignment.Center) { mark() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

private const val SITE_URL = "https://trackhop.org"

/** GitHub's mark (Simple Icons, CC0), only to show where the link goes. */
private val GitHubGlyph: ImageVector by lazy {
    ImageVector.Builder("github", 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes("M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"), fill = SolidColor(Color.White))
        .build()
}

@Composable
private fun HeroIcon(icon: ImageVector, size: Dp = 56.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * One thing the app does, on a card of its own as the rows of Tools and Settings are: the sign on a
 * tile in [accent] (the colour its tab wears), a title and a line under it.
 */
@Composable
private fun StepRow(icon: ImageVector, title: String, desc: String, accent: JobColor? = null) {
    val dark = LocalAppDark.current
    val color = accent?.let { if (dark) it.dark else it.light } ?: MaterialTheme.colorScheme.primary
    PageCard {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(lerp(MaterialTheme.colorScheme.surfaceContainerHighest, color, if (dark) 0.28f else 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (dark) color else lerp(color, MaterialTheme.colorScheme.onSurface, 0.25f), modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
