package com.xlollx.songport.sync

import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.RadioStation
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider

/**
 * Radio stations and Shazam as connectors (Accounts, the service chooser's "Radio & charts").
 *
 * A radio connector is one station ("radio" for the first, then "radio@2", "radio@3"…), its
 * station kept in [Settings.radioStations]; as many as wanted. Shazam is one connector, "shazam".
 * A sync from either is the radio or Shazam sync it always was: a radio sync notes what the station
 * plays in a File playlist of its own, its source, and listens in its window ([SyncJob.radio]); a
 * Shazam sync reads the playlist the Shazam app fills. [SyncJob.via] names the connector it comes
 * through, which the app and the server show as its source.
 *
 * Syncs made before the connectors are brought to them by [normalized], which the store applies
 * whenever it reads its state or its syncs change (a start, a configuration file, a backup): every
 * radio sync gets the connector of its station (made when there is none yet, one per stream), every
 * Shazam sync the Shazam connector. Nothing else of the sync changes. Applied again, it changes nothing.
 */
object RadioConnectors {
    const val SERVICE = "radio"
    const val SHAZAM = "shazam"

    /** True for a radio connector's id: "radio", "radio@2"… */
    fun isRadio(id: String?): Boolean = id != null && (id == SERVICE || id.startsWith("$SERVICE@"))

    /** Two addresses of one stream: the same but for case, blanks and a closing slash. */
    fun sameStream(a: String, b: String): Boolean = norm(a) == norm(b)

    private fun norm(s: String) = s.trim().trimEnd('/').lowercase()

    /** The connector [s] keeps for [stream], or null. */
    fun forStream(s: Settings, stream: String): String? =
        s.radioStations.entries.firstOrNull { sameStream(it.value.stream, stream) }?.key

    /** The connector a sync's source is shown as: its [SyncJob.via], else its source's connector. */
    fun sourceOf(job: SyncJob): String = job.via ?: job.source.provider

    /** The syncs that come through connector [id]. */
    fun syncsOf(jobs: List<SyncJob>, id: String): List<SyncJob> = jobs.filter { it.via == id }

    /** The next numbered slot of the radio connectors: after the highest in use, starting at 2. */
    private fun nextSlot(s: Settings): String {
        val used = s.extraAccounts[SERVICE].orEmpty() + s.radioStations.keys.filter { it.startsWith("$SERVICE@") }.map { it.substringAfter('@') }
        return ((used.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 1) + 1).toString()
    }

    /**
     * [s] with a connector for [station], and its id. [reuse]: the connector already kept for the
     * same stream, when there is one, rather than a second (put back in the Accounts list if it was
     * taken off). The first station takes the main slot ("radio"), the next ones a numbered slot.
     * [name] and [color] (an AccountColors key) are the connector's own, as for any account.
     */
    fun add(s: Settings, station: RadioStation, name: String? = null, color: String? = null, reuse: Boolean = true): Pair<Settings, String> {
        val existing = if (reuse) forStream(s, station.stream) else null
        if (existing != null) return s.copy(connectors = (s.connectors + existing).distinct()) to existing
        val main = s.radioStations[SERVICE] == null
        val slot = if (main) "" else nextSlot(s)
        val id = if (main) SERVICE else "$SERVICE@$slot"
        val given = name?.trim()?.takeIf { it.isNotEmpty() && it != station.station }
        val next = s.copy(
            connectors = (s.connectors + id).distinct(),
            radioStations = s.radioStations + (id to station),
            extraAccounts = if (main) s.extraAccounts else s.extraAccounts + (SERVICE to (s.extraAccounts[SERVICE].orEmpty() + slot).distinct()),
            connectorNames = if (given == null) s.connectorNames else s.connectorNames + (id to given),
        )
        val colored = color?.let { com.xlollx.songport.data.AccountColors.hex(it)?.let { _ -> next.copy(connectorColors = next.connectorColors + (id to it)) } } ?: next
        return colored to id
    }

    /**
     * [s] without radio connector [id] and all it kept (its station, name, colour, slot). The caller
     * checks first that no sync comes through it ([syncsOf]).
     */
    fun remove(s: Settings, id: String): Settings = s.copy(
        connectors = s.connectors - id,
        radioStations = s.radioStations - id,
        connectorNames = s.connectorNames - id,
        connectorColors = s.connectorColors - id,
        extraAccounts = if (!id.contains('@')) s.extraAccounts
        else (s.extraAccounts + (SERVICE to s.extraAccounts[SERVICE].orEmpty().filter { it != id.substringAfter('@') })).filterValues { it.isNotEmpty() },
    )

    /** [s] with the Shazam connector in the Accounts list. */
    fun addShazam(s: Settings): Settings = if (SHAZAM in s.connectors) s else s.copy(connectors = s.connectors + SHAZAM)

    /** True when [job] is not yet where [normalized] puts it. */
    private fun needs(job: SyncJob, s: Settings): Boolean {
        val radio = job.radio
        return when {
            radio != null -> job.via == null || s.radioStations[job.via]?.let { !sameStream(it.stream, radio.stream) } ?: true
            job.shazam -> job.via != SHAZAM
            else -> job.via != null
        }
    }

    /**
     * [d] with every radio sync on its station's connector and every Shazam sync on Shazam's (see
     * the object's note). A sync on a connector whose stream is not its own (a configuration file
     * that brought another station under the same id) goes to its own station's. A sync that is no
     * longer a radio or Shazam sync loses [SyncJob.via]. [d] itself when there is nothing to do.
     */
    fun normalized(d: StoreData): StoreData {
        if (d.jobs.none { needs(it, d.settings) }) return d
        var s = d.settings
        val jobs = d.jobs.map { j ->
            if (!needs(j, s)) return@map j
            val radio = j.radio
            when {
                radio != null -> {
                    val (next, id) = add(s, RadioStation(radio.station, radio.stream, radio.homepage))
                    s = next
                    j.copy(via = id)
                }
                j.shazam -> {
                    s = addShazam(s)
                    j.copy(via = SHAZAM)
                }
                else -> j.copy(via = null)
            }
        }
        return d.copy(settings = s, jobs = jobs)
    }

    /** A new sync's window, as before the connectors: every day, 08:00 to 10:00. */
    val DEFAULT_DAYS = (1..7).toList()
    const val DEFAULT_START = 8 * 60
    const val DEFAULT_END = 10 * 60

    /**
     * A new sync from radio connector [connectorId] to [target]: its File playlist ("Radio - <station>")
     * made now, the window [days], [startMinute]..[endMinute], manual (it runs by its window), never
     * mirrored, on Wi-Fi only unless said otherwise, as a radio sync always started. Not saved: the
     * caller adds it to the store. Null when [connectorId] is no radio connector with a station.
     */
    suspend fun newSync(
        env: Env, connectorId: String, target: PlaylistRef, id: String, name: String? = null,
        days: List<Int> = DEFAULT_DAYS, startMinute: Int = DEFAULT_START, endMinute: Int = DEFAULT_END, wifiOnly: Boolean = false,
    ): SyncJob? {
        val station = env.store.data.settings.radioStations[connectorId]?.takeIf { isRadio(connectorId) } ?: return null
        val log = LocalFilesProvider.createPlaylist(env, "Radio - ${station.station}", "")
        return SyncJob(
            id = id,
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: "${station.station} radio",
            source = PlaylistRef(LocalFilesProvider.id, log.id, log.name),
            target = target,
            schedule = Schedule.MANUAL,
            wifiOnly = wifiOnly,
            radio = RadioSource(station.station, station.stream, station.homepage, days.filter { it in 1..7 }.distinct().sorted(), startMinute, endMinute),
            via = connectorId,
        )
    }
}
