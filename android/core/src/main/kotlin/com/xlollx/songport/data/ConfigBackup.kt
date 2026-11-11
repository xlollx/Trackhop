package com.xlollx.songport.data

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.Timing
import com.xlollx.songport.sync.Wrapped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Trackhop's whole set-up in one file (`.trackhop`), to move it to another phone or between the
 * app, Docker and Trackhop Desktop, which keep the same data: the syncs, the services added (not
 * signed in), the matches chosen by hand and the tracks ignored, the settings, the File playlists
 * and, if asked, the run history.
 *
 * Never in it: sign-ins and passwords (the token vault), client secrets, the app lock, and what only
 * means something on the device that wrote it (the Android backup folder, caches of failed
 * searches). After an import the services are signed in again, and the syncs carry on where they were.
 */
object ConfigBackup {
    const val FORMAT = "trackhop-config"
    const val VERSION = 1
    const val EXTENSION = "trackhop"

    @Serializable
    data class Snapshot(
        val format: String = FORMAT,
        val version: Int = VERSION,
        /** Who wrote it: "Trackhop 1.3.9 (Android)", "Trackhop server 0.4.0"… for the import's message. */
        val writtenBy: String = "",
        val writtenAt: Long = 0,
        val jobs: List<SyncJob> = emptyList(),
        val reports: List<SyncReport> = emptyList(),
        val matchCache: Map<String, String> = emptyMap(),
        val ignoredTracks: Set<String> = emptySet(),
        val settings: Settings = Settings(),
        /** File playlists: name (without .csv) -> the CSV's text. */
        val files: Map<String, String> = emptyMap(),
        /** The Wrapped history, with the run history only (it is the user's own past, like the runs). */
        val wrapped: List<Wrapped.Saved> = emptyList(),
        /** The installation that wrote it ([Settings.deviceId]) and how it calls itself ("Pixel 8"); "" in older files. */
        val device: String = "",
        val deviceName: String = "",
    )

    /**
     * [paused]: scheduled syncs that came from another device and were turned off here, so they do
     * not run on both; [from] is how that device calls itself, for the notice.
     */
    data class Summary(
        val syncs: Int, val matches: Int, val files: Int, val runs: Int, val writtenBy: String, val writtenAt: Long,
        val paused: Int = 0, val from: String = "",
    )

    /** This installation's id, made on first use. */
    fun deviceId(env: Env): String {
        env.store.data.settings.deviceId.takeIf { it.isNotBlank() }?.let { return it }
        env.store.updateSettings { if (it.deviceId.isBlank()) it.copy(deviceId = java.util.UUID.randomUUID().toString()) else it }
        return env.store.data.settings.deviceId
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** The set-up as the file's text. */
    fun export(env: Env, writtenBy: String, history: Boolean = false, now: Long = System.currentTimeMillis(), deviceName: String = ""): String {
        val device = deviceId(env)
        val d = env.store.data
        val files = LocalFilesProvider.dir(env).listFiles { f -> f.isFile && f.extension == "csv" }.orEmpty()
            .associate { it.nameWithoutExtension to it.readText() }
        val snapshot = Snapshot(
            writtenBy = writtenBy, writtenAt = now,
            // A shared playlist's syncs stay: the group's key is this device's, and does not travel.
            jobs = d.syncs.filter { com.xlollx.songport.shared.SharedGroups.sharedSide(it) == null }.let { jobs -> if (history) jobs else jobs.map { it.copy(lastReportId = null) } },
            reports = if (history) d.reports else emptyList(),
            matchCache = d.matchCache, ignoredTracks = d.ignoredTracks,
            settings = portable(d.settings), files = files,
            wrapped = if (history) d.wrapped else emptyList(),
            device = device, deviceName = deviceName,
        )
        return json.encodeToString(Snapshot.serializer(), snapshot)
    }

    /** What a file holds, without changing anything; throws with a readable message if it is not one. */
    fun read(text: String): Snapshot {
        val s = runCatching { json.decodeFromString(Snapshot.serializer(), text.removePrefix("﻿")) }
            .getOrElse { throw IllegalArgumentException("Not a Trackhop configuration file.") }
        require(s.format == FORMAT) { "Not a Trackhop configuration file." }
        require(s.version <= VERSION) { "This file comes from a newer Trackhop: update this one first." }
        return s
    }

    fun summary(s: Snapshot) = Summary(s.jobs.size, s.matchCache.size, s.files.size, s.reports.size, s.writtenBy, s.writtenAt)

    /**
     * Brings [s] in: its syncs replace those with the same id and join the others; matches, ignored
     * tracks and services added are merged; its settings replace these except what belongs to this
     * device; its File playlists are added, a name already here kept as it is.
     *
     * A file written by another device brings its scheduled syncs turned off ([SyncJob.scheduledOn]):
     * left on, they would run here and there, and both would add what is missing. One already on
     * here stays on: that choice was made. A file this device wrote itself comes back as it is.
     */
    fun import(env: Env, s: Snapshot): Summary {
        val foreign = s.device != deviceId(env)
        val from = s.deviceName.ifBlank { s.writtenBy }
        var paused = 0
        val dir = LocalFilesProvider.dir(env)
        var files = 0
        for ((name, text) in s.files) {
            val safe = CsvCodec.safeName(name)
            val f = File(dir, "$safe.csv")
            if (!f.exists()) { f.writeText(text); files++ }
        }
        env.store.update { d ->
            val ids = s.jobs.map { it.id }.toSet()
            val reportIds = d.reports.map { it.id }.toSet()
            // A file from before labels and tags has none: the store takes its colours as labels (FileStore.update).
            val here = d.jobs.associateBy { it.id }
            paused = 0
            val incoming = s.jobs.map { j ->
                val runsByItself = j.enabled && (Timing.interval(j) > 0 || j.days.isNotEmpty() || j.radio != null)
                when {
                    !foreign || !runsByItself -> j
                    here[j.id]?.enabled == true -> j.copy(scheduledOn = null)
                    else -> j.copy(enabled = false, scheduledOn = from).also { paused++ }
                }
            }
            d.copy(
                jobs = d.jobs.filter { it.id !in ids } + incoming,
                reports = (d.reports + s.reports.filter { it.id !in reportIds }).sortedByDescending { it.startedEpoch }.take(FileStore.MAX_REPORTS),
                matchCache = d.matchCache + s.matchCache,
                ignoredTracks = d.ignoredTracks + s.ignoredTracks,
                settings = merged(d.settings, s.settings),
                wrapped = s.wrapped.fold(d.wrapped) { h, w -> Wrapped.remember(h, w) },
            )
        }
        // The services added come with it: the connector list follows the new settings at once.
        env.store.data.settings.let { st -> com.xlollx.songport.providers.Providers.configure(st.extraAccounts, st.connectorNames, st.connectors) }
        env.store.flush()
        return Summary(s.jobs.size, s.matchCache.size, files, s.reports.size, s.writtenBy, s.writtenAt, paused, from)
    }

    /** The settings without what is secret or only means something on this device. */
    private fun portable(st: Settings) = st.copy(
        clientSecrets = emptyMap(), appLock = false, backupFolder = "",
        supportPromptReport = null, appOpens = 0, supportNudgeSeen = 0, deviceId = "",
        // Which "What's new" this phone has shown: the phone that imports the file keeps its own.
        whatsNewSeen = "",
    )

    /** [incoming]'s settings over [current], keeping this device's own. */
    private fun merged(current: Settings, incoming: Settings) = incoming.copy(
        clientSecrets = current.clientSecrets,
        appLock = current.appLock,
        backupFolder = current.backupFolder,
        backupSchedule = current.backupSchedule,
        connectors = (current.connectors + incoming.connectors).distinct(),
        connectorNames = current.connectorNames + incoming.connectorNames,
        extraAccounts = (current.extraAccounts.keys + incoming.extraAccounts.keys).associateWith { k ->
            ((current.extraAccounts[k] ?: emptyList()) + (incoming.extraAccounts[k] ?: emptyList())).distinct()
        },
        clientIds = current.clientIds + incoming.clientIds,
        connectorsInit = true,
        onboardingDone = current.onboardingDone || incoming.onboardingDone,
        tourDone = current.tourDone || incoming.tourDone,
        tipsPillDismissed = current.tipsPillDismissed || incoming.tipsPillDismissed,
        supportPromptReport = current.supportPromptReport,
        supportPromptDone = current.supportPromptDone,
        appOpens = current.appOpens,
        supportNudgeSeen = current.supportNudgeSeen,
        whatsNewSeen = current.whatsNewSeen,
        // The labels of both: the file's win by id, this device's others stay for the syncs that wear them.
        labels = incoming.labels + current.labels.filter { c -> incoming.labels.none { it.id == c.id } },
        deviceId = current.deviceId,
    )

    /** The file's name: trackhop-2026-10-30.trackhop. */
    fun fileName(now: Long = System.currentTimeMillis()): String =
        "trackhop-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(now)) + ".$EXTENSION"
}
