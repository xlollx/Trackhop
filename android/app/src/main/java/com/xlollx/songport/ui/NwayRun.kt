package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.sync.SyncState

/**
 * A multi sync while it runs: which of its services is being synced now, which are done, which
 * wait, which failed. Read from the syncs running ([SyncState.running]) and, for a "Sync now" on
 * the whole group, from the worker's record of the batch ([SyncState.Batch]). Kotlin puro: coperto dai test.
 */
internal object NwayRun {
    /** IDLE: not part of what runs now (one service run alone, by its own schedule: the others just stand by). */
    enum class Phase { DONE, NOW, WAITING, FAILED, IDLE }

    data class Service(
        val member: Nway.Member, val phase: Phase,
        /** The sync of this service running now ([Phase.NOW]). */
        val job: SyncJob? = null,
        /** How its syncs of this run ended, added up ([Phase.DONE]), or the one that failed ([Phase.FAILED]). */
        val result: SyncState.JobResult? = null,
    ) {
        val provider: String get() = member.playlist.provider
    }

    data class State(
        val services: List<Service>,
        /** The service being synced now; none for the moment between two syncs of a batch. */
        val now: Service?,
        /** Where the run stands among the services, from 1 ("3 of 6"); 0 when a service runs alone. */
        val place: Int, val total: Int,
        /** How far the whole run is, 0 to 1; null when not known (the bar then just moves). */
        val fraction: Float?,
        /** The step of the sync running now. */
        val progress: Progress?,
    )

    /** Null when nothing of the group runs. [members]: the source first, as the group's page lists them. */
    fun of(members: List<Nway.Member>, running: Map<String, Progress?>, batch: SyncState.Batch?): State? {
        fun jobs(m: Nway.Member) = listOfNotNull(m.up, m.down)
        val ids = members.flatMap { jobs(it) }.mapTo(HashSet()) { it.id }
        val live = running.keys.any { it in ids }
        if (!live && batch == null) return null
        val services = members.map { m ->
            val mine = jobs(m)
            val active = mine.firstOrNull { it.id in running }
            val asked = mine.filter { batch != null && it.id in batch.order }
            val ended = asked.mapNotNull { j -> batch?.outcomes?.get(j.id)?.takeIf { j.id in batch.finished } }
            val failed = ended.firstOrNull { it.failed }
            when {
                active != null -> Service(m, Phase.NOW, job = active)
                failed != null -> Service(m, Phase.FAILED, result = failed)
                asked.isNotEmpty() && ended.size == asked.size -> Service(m, Phase.DONE, result = ended.reduce { a, b ->
                    a.copy(ok = a.ok && b.ok, offline = a.offline || b.offline, added = a.added + b.added, removed = a.removed + b.removed, notFound = a.notFound + b.notFound, held = a.held + b.held)
                }.let { r ->
                    // Added and removed on this service's playlist only (its down sync), as the card's
                    // figures and lists count them: what its up sync wrote went to the group's own list.
                    val down = batch?.outcomes?.get(m.down.id)?.takeIf { m.down.id in batch.finished }
                    r.copy(added = down?.added ?: 0, removed = down?.removed ?: 0)
                })
                asked.isNotEmpty() -> Service(m, Phase.WAITING)
                else -> Service(m, Phase.IDLE)
            }
        }
        val now = services.firstOrNull { it.phase == Phase.NOW }
        val progress = now?.job?.let { running[it.id] }
        if (batch == null) return State(services, now, 0, services.size, progress?.percent?.let { it / 100f }, progress)
        val inRun = services.filter { it.phase != Phase.IDLE }
        val over = inRun.count { it.phase == Phase.DONE || it.phase == Phase.FAILED }
        val steps = batch.order.size
        val fraction = if (steps == 0) null else ((batch.finished.size + (progress?.percent ?: 0) / 100f) / steps).coerceIn(0f, 1f)
        return State(services, now, (over + 1).coerceAtMost(inRun.size), inRun.size, fraction, progress)
    }

    /** The members with the group's source first ([sourceJobId]: the sync the group's card stands on), the others in their order. */
    fun sourceFirst(members: List<Nway.Member>, sourceJobId: String?): List<Nway.Member> =
        members.sortedBy { if (it.down.id == sourceJobId) 0 else 1 }
}

/**
 * A multi sync as its card and its tile show it ([JobCard], [JobTile] with this over them): the
 * group, its services with the source first, how they stand at rest and, while it runs, where the
 * run is.
 */
internal class GroupCard(val group: com.xlollx.songport.shared.SharedGroup, val members: List<Nway.Member>, val status: NwayStatus.Group, val run: NwayRun.State?) {
    /** The sync being run now, when a service is being synced. */
    val runningJob: SyncJob? get() = run?.now?.job
    val sourceProvider: String? get() = members.firstOrNull()?.playlist?.provider
    /** The first target: with the source, the two colours of the card's glow. */
    val targetProvider: String? get() = members.getOrNull(1)?.playlist?.provider
}

/** [GroupCard] of [group] from the store's data ([d], with every sync of the group) and the syncs running. */
@Composable
internal fun rememberGroupCard(group: com.xlollx.songport.shared.SharedGroup, d: com.xlollx.songport.data.StoreData, running: Map<String, Progress?>): GroupCard {
    val batches by SyncState.batches.collectAsState()
    val members = remember(d.jobs, group.id, group.jobId) { Nway.members(d.jobs, group.id).let { all -> NwayRun.sourceFirst(all, Nway.source(group, all)?.down?.id) } }
    val status = remember(d.jobs, d.reports, members) { NwayStatus.group(d, members) }
    return GroupCard(group, members, status, NwayRun.of(members, running, SyncState.batchOf(group.id, batches)))
}

/** How many logos a multi sync's tile shows before "+N": the source, two targets. */
internal const val GROUP_LOGOS_MAX = 3

/** The logo of a service of a multi sync, by where it stands in the run (for the UI tests): "group-logo-now:tidal". */
internal fun groupLogoTag(phase: NwayRun.Phase?, provider: String) = "group-logo-" + (phase?.name?.lowercase() ?: "rest") + ":" + provider

/**
 * A multi sync's services, overlapping as a sync's two logos do: at most [max] and then "+N". The
 * source has a light edge. While it runs ([run]) the one being synced wears a ring, the ones done a
 * tick, the ones that failed a mark, and the ones waiting are dimmed.
 */
@Composable
internal fun GroupLogos(members: List<Nway.Member>, run: NwayRun.State?, size: Dp, max: Int = GROUP_LOGOS_MAX, modifier: Modifier = Modifier) {
    val shown = if (members.size > max + 1) members.take(max) else members
    val more = members.size - shown.size
    // While it runs the service at work is always among the logos shown: it takes the last place.
    val now = run?.now?.member
    val list = if (now != null && now !in shown) shown.dropLast(1) + now else shown
    val step = size * 0.64f
    val ring = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(step * (list.size - 1 + if (more > 0) 1 else 0) + size + 2.dp).padding(vertical = 1.dp)) {
            list.forEachIndexed { i, m ->
                val phase = run?.services?.firstOrNull { it.member.down.id == m.down.id }?.phase
                Box(Modifier.offset(x = step * i).testTag(groupLogoTag(phase, m.playlist.provider))) {
                    ServiceLogo(
                        Providers.byId(m.playlist.provider), size,
                        Modifier.alpha(if (phase == NwayRun.Phase.WAITING) 0.4f else 1f).then(
                            when {
                                phase == NwayRun.Phase.NOW -> Modifier.border(2.dp, ring, CircleShape)
                                // The source: a light edge, at rest too.
                                i == 0 && now == null -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), CircleShape)
                                else -> Modifier
                            },
                        ),
                    )
                    val mark = when (phase) { NwayRun.Phase.DONE -> Icons.Filled.Check; NwayRun.Phase.FAILED -> Icons.Filled.PriorityHigh; else -> null }
                    if (mark != null) Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(size * 0.42f).clip(CircleShape)
                            .background(if (phase == NwayRun.Phase.FAILED) MaterialTheme.colorScheme.error else Tones.onSuccessContainer()).border(1.dp, surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(mark, null, Modifier.size(size * 0.3f), tint = if (phase == NwayRun.Phase.FAILED) MaterialTheme.colorScheme.onError else Tones.successContainer()) }
                }
            }
            if (more > 0) Box(
                Modifier.offset(x = step * list.size).size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(1.dp, surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("+$more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** "TIDAL · 3 of 6": the service being synced and where the run stands; the service alone when it runs by itself. */
@Composable
internal fun groupRunText(run: NwayRun.State): String {
    val name = run.now?.let { nwayMember(it.member) }
    return when {
        name != null && run.place > 0 -> stringResource(R.string.nway_run_place, name, run.place, run.total)
        name != null -> name
        run.place > 0 -> stringResource(R.string.nway_run_services, run.place, run.total)
        else -> stringResource(R.string.pill_running)
    }
}

/** For the UI tests: the row of the bar of a multi sync at work, and the small logo beside it. */
internal const val GROUP_BAR_TAG = "group-bar"

/**
 * The bar of a multi sync at work, over the whole run, with the small logo of the service being
 * synced now right beside it: which service the bar is moving for, without reading the line above.
 */
@Composable
internal fun GroupWorkBar(run: NwayRun.State, modifier: Modifier = Modifier, logo: Dp = 16.dp) {
    val now = run.now
    val said = now?.let { stringResource(R.string.nway_run_now_desc, nwayMember(it.member)) }
    Row(modifier.testTag(GROUP_BAR_TAG).then(if (said != null) Modifier.semantics(mergeDescendants = true) { contentDescription = said } else Modifier), verticalAlignment = Alignment.CenterVertically) {
        if (now != null) {
            // Said once, by the row: the logo itself is a picture of the same thing. It takes no height of
            // its own: the row is as tall as the bar (or as its caller makes it), so a running multi sync's
            // card and tile are as tall as any other sync's (2.1.2); a larger logo reaches over the space around.
            // Its tag and semantics come after that layout, on the logo as drawn: on the zero-height box
            // around it they would have no bounds at all.
            Box(
                Modifier.layout { m, c ->
                    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    layout(p.width, 0) { p.place(0, -p.height / 2) }
                }.testTag("$GROUP_BAR_TAG:${now.provider}").clearAndSetSemantics { },
            ) { ServiceLogo(Providers.byId(now.provider), logo) }
            Spacer(Modifier.width(8.dp))
        }
        val f = run.fraction
        WorkBar(f?.let { v -> { v } }, Modifier.weight(1f).fillMaxWidth())
    }
}
