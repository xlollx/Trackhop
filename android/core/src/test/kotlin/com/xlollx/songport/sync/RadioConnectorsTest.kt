package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.RadioStation
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.RadioProvider
import com.xlollx.songport.providers.ServiceCatalog
import com.xlollx.songport.providers.ShazamProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Radio stations and Shazam as connectors (2.2): the radio and Shazam syncs made before them are
 * brought to their connector without anything else of theirs changing, once, at every way in (the
 * store at start, a configuration file, a backup), and a sync from a connector is the same sync.
 */
class RadioConnectorsTest {
    private val lenient = Json { ignoreUnknownKeys = true }

    private val virgin = "http://icecast.unitedradio.it/Virgin.mp3"
    private val r105 = "https://icy.unitedradio.it/Radio105.mp3"

    /**
     * A store.json as Trackhop 2.1 wrote it: no connector for radios or Shazam, radio syncs on a File
     * playlist with their station inside, a Shazam sync on Spotify's "My Shazam Tracks". Every field
     * a radio or Shazam sync can carry is set to something other than its default.
     */
    private val oldStore = """
{"jobs":[
 {"id":"r1","name":"Virgin mornings","source":{"provider":"csv","playlistId":"Radio - Virgin Radio","playlistName":"Radio - Virgin Radio"},
  "target":{"provider":"spotify","playlistId":"sp1","playlistName":"Virgin Radio radio"},"schedule":"MANUAL","wifiOnly":true,"enabled":true,
  "lastRunEpoch":1763000000000,"lastReportId":"rep1","ignoredSourceIds":["t9","t10"],"sourceVersion":"abc|12","targetVersion":"v7",
  "versionsKey":42,"fullRunEpoch":1763000000001,"policy":{"strictness":1,"explicit":-1,"sameAlbum":true,"studioOnly":true},
  "color":"teal","accentStyle":"band","routeGlow":false,"favorite":true,"label":"teal","tags":{"genre":"pop","car":""},
  "radio":{"station":"Virgin Radio","stream":"$virgin","homepage":"https://www.virginradio.it","days":[1,2,3,4,5],"startMinute":480,"endMinute":600},
  "copyRatings":false,"mirrorRemovals":false,"keepOrder":false,"createdEpoch":5},
 {"id":"r2","name":"Virgin nights","source":{"provider":"csv","playlistId":"Radio - Virgin Radio (2)","playlistName":"Radio - Virgin Radio (2)"},
  "target":{"provider":"ytm","playlistName":"Nights"},"radio":{"station":"Virgin","stream":"${virgin.uppercase().replace("HTTP:", "http:")}/","days":[6,7],"startMinute":1320,"endMinute":120},
  "enabled":false,"scheduledOn":"Pixel 8"},
 {"id":"r3","name":"105","source":{"provider":"csv","playlistId":"Radio - Radio 105","playlistName":"Radio - Radio 105"},
  "target":{"provider":"subsonic@2","playlistId":"pl5","playlistName":"105"},"radio":{"station":"Radio 105","stream":"$r105","days":[1,2,3,4,5,6,7],"startMinute":0,"endMinute":0}},
 {"id":"s1","name":"My Shazams","source":{"provider":"spotify","playlistId":"shz","playlistName":"My Shazam Tracks"},"target":{"provider":"apple","playlistId":"ap1","playlistName":"Shazams"},
  "schedule":"HOURLY","everyMinutes":60,"shazam":true,"ignoredSourceIds":["x1"],"scheduleId":"hourly","lastRunEpoch":1763000000002,"sourceVersion":"snap1","color":"#FF0088FF"},
 {"id":"p1","name":"Plain","source":{"provider":"spotify","playlistId":"a"},"target":{"provider":"ytm","playlistId":"b"},"schedule":"DAILY"}
],
"reports":[{"id":"rep1","jobId":"r1","jobName":"Virgin mornings","startedEpoch":1763000000000,"durationMs":1200,"sourceCount":30,"added":3}],
"matchCache":{"csv:t1>spotify":"sp-t1","spotify:shz1>apple":"ap-1"},
"ignoredTracks":["csv:t2>spotify"],
"settings":{"connectors":["spotify","ytm","apple","subsonic@2","csv"],"extraAccounts":{"subsonic":["2"]},"connectorsInit":true,
  "labels":[{"id":"teal","color":"teal","name":"Radio"}],"savedSchedules":[{"id":"hourly","name":"Hourly","everyMinutes":60}]}}
"""

    private fun oldDir(): File = Files.createTempDirectory("radio-old").toFile().also { File(it, "store.json").writeText(oldStore) }

    /** The old syncs as a 2.2 store reads them before anything is done (`via` null): the reference for every field. */
    private fun oldData(): StoreData = Schedules.normalized(null, Labels.normalized(lenient.decodeFromString(StoreData.serializer(), oldStore)))

    @Test fun anOldStoreIsBroughtToTheConnectorsWithEveryFieldKept() {
        val before = oldData()
        val d = FileStore(oldDir()).data
        val via = mapOf("r1" to "radio", "r2" to "radio", "r3" to "radio@2", "s1" to "shazam", "p1" to null)
        // Every sync is exactly what it was, but for the connector it comes through.
        assertEquals(before.jobs.map { it.copy(via = via[it.id]) }, d.jobs)
        // The two syncs of one station (the same stream however written) share its connector.
        assertEquals(
            mapOf("radio" to RadioStation("Virgin Radio", virgin, "https://www.virginradio.it"), "radio@2" to RadioStation("Radio 105", r105)),
            d.settings.radioStations,
        )
        assertEquals(listOf("spotify", "ytm", "apple", "subsonic@2", "csv", "radio", "radio@2", "shazam"), d.settings.connectors)
        assertEquals(mapOf("subsonic" to listOf("2"), "radio" to listOf("2")), d.settings.extraAccounts)
        // What the runs found and the user chose stays as it was: matches, ignored songs, reports, labels, schedules.
        assertEquals(before.matchCache, d.matchCache)
        assertEquals(before.ignoredTracks, d.ignoredTracks)
        assertEquals(before.reports, d.reports)
        assertEquals(before.settings.labels, d.settings.labels)
        assertEquals(before.settings.savedSchedules, d.settings.savedSchedules)
        assertEquals(before.settings.copy(connectors = d.settings.connectors, extraAccounts = d.settings.extraAccounts, radioStations = d.settings.radioStations), d.settings)
        // Spot checks that the old file did carry them (a field silently dropped by both would pass the equality above).
        d.jobs.first { it.id == "r1" }.let { j ->
            assertEquals(RadioSource("Virgin Radio", virgin, "https://www.virginradio.it", listOf(1, 2, 3, 4, 5), 480, 600), j.radio)
            assertEquals(PlaylistRef(LocalFilesProvider.id, "Radio - Virgin Radio", "Radio - Virgin Radio"), j.source)
            assertEquals(listOf("t9", "t10"), j.ignoredSourceIds)
            assertTrue(j.wifiOnly && j.favorite)
            assertEquals("rep1", j.lastReportId)
            assertEquals(mapOf("genre" to "pop", "car" to ""), j.tags)
            assertEquals(1, j.policy.strictness)
        }
        d.jobs.first { it.id == "s1" }.let { j ->
            assertTrue(j.shazam)
            assertEquals(PlaylistRef("spotify", "shz", "My Shazam Tracks"), j.source)
            assertEquals(60, j.everyMinutes)
            assertEquals("hourly", j.scheduleId)
        }
        assertEquals("Pixel 8", d.jobs.first { it.id == "r2" }.scheduledOn)
        assertFalse(d.jobs.first { it.id == "r2" }.enabled)
    }

    @Test fun theMigrationIsDoneOnceAndSurvivesAReload() {
        val dir = oldDir()
        val first = FileStore(dir)
        val once = first.data
        assertSame(once, RadioConnectors.normalized(once))
        // Any later change leaves the connectors as they are: no second connector for a station.
        first.update { it.copy(jobs = it.jobs.map { j -> if (j.id == "p1") j.copy(name = "Plain 2") else j }) }
        assertEquals(once.settings, first.data.settings)
        first.flush()
        val again = FileStore(dir).data
        assertEquals(first.data.jobs, again.jobs)
        assertEquals(first.data.settings, again.settings)
        assertSame(again, RadioConnectors.normalized(again))
    }

    @Test fun anOldConfigurationFileRestoresIntoTheConnectors() {
        val before = oldData()
        // The same syncs, settings and matches in a configuration file written by 2.1, with a File playlist.
        val old = lenient.parseToJsonElement(oldStore).jsonObject
        val file = buildJsonObject {
            put("format", ConfigBackup.FORMAT)
            put("version", 1)
            put("writtenBy", "Trackhop 2.1.5 (Android)")
            put("jobs", old["jobs"]!!)
            put("matchCache", old["matchCache"]!!)
            put("ignoredTracks", old["ignoredTracks"]!!)
            put("settings", old["settings"]!!)
            put("files", buildJsonObject { put("Radio - Virgin Radio", "title,artist\nSong,Singer\n") })
        }.toString()
        val env = ContractEnv()
        ConfigBackup.import(env, ConfigBackup.read(file), replacesOther = true)
        val d = env.store.data
        val via = mapOf("r1" to "radio", "r2" to "radio", "r3" to "radio@2", "s1" to "shazam", "p1" to null)
        assertEquals(before.jobs.map { it.copy(via = via[it.id]) }.sortedBy { it.id }, d.jobs.sortedBy { it.id })
        assertEquals(setOf("radio", "radio@2"), d.settings.radioStations.keys)
        assertTrue(d.settings.connectors.containsAll(listOf("radio", "radio@2", "shazam")))
        assertEquals("sp-t1", d.matchCache["csv:t1>spotify"])
        // The songs heard so far came with it, in the radio sync's own File playlist.
        assertTrue(File(LocalFilesProvider.dir(env), "Radio - Virgin Radio.csv").exists())
    }

    @Test fun aFileThatGivesAnotherStationTheSameIdMovesThisDevicesSyncToItsOwnStation() {
        // This device: 105 on "radio". The file: Virgin on "radio".
        val here = ContractEnv()
        here.store.update { StoreData(jobs = listOf(radioJob("h1", "Radio 105", r105))) }
        assertEquals("radio", here.store.data.jobs[0].via)
        val other = ContractEnv()
        other.store.update { StoreData(jobs = listOf(radioJob("o1", "Virgin Radio", virgin))) }
        ConfigBackup.import(here, ConfigBackup.read(ConfigBackup.export(other, "test")), replacesOther = true)
        val d = here.store.data
        val h1 = d.jobs.first { it.id == "h1" }
        val o1 = d.jobs.first { it.id == "o1" }
        assertEquals("radio", o1.via)
        assertEquals(virgin, d.settings.radioStations[o1.via]!!.stream)
        assertEquals(r105, d.settings.radioStations[h1.via]!!.stream)
        assertTrue(h1.via != o1.via)
        // Exported and imported again: nothing new.
        val twice = ContractEnv()
        ConfigBackup.import(twice, ConfigBackup.read(ConfigBackup.export(here, "test")), replacesOther = true)
        assertEquals(d.settings.radioStations, twice.store.data.settings.radioStations)
        assertEquals(d.jobs.sortedBy { it.id }.map { it.via }, twice.store.data.jobs.sortedBy { it.id }.map { it.via })
    }

    private fun radioJob(id: String, station: String, stream: String) = SyncJob(
        id = id, name = id, source = PlaylistRef(LocalFilesProvider.id, "Radio - $station", "Radio - $station"),
        target = PlaylistRef("spotify", null, station), radio = RadioSource(station, stream),
    )

    @Test fun addingAStationTwiceGivesTheSameConnectorAndEachOtherOneItsOwnSlot() {
        var s = Settings(connectors = listOf("spotify"))
        val (s1, a) = RadioConnectors.add(s, RadioStation("Virgin Radio", virgin), name = "Virgin", color = "red")
        s = s1
        assertEquals("radio", a)
        assertEquals("Virgin", s.connectorNames["radio"])
        assertEquals("red", s.connectorColors["radio"])
        val (s2, again) = RadioConnectors.add(s, RadioStation("Virgin", "$virgin/"))
        assertEquals("radio", again)
        assertEquals(s, s2)
        val (s3, b) = RadioConnectors.add(s, RadioStation("Radio 105", r105))
        assertEquals("radio@2", b)
        val (s4, c) = RadioConnectors.add(s3, RadioStation("KEXP", "https://kexp.streamguys1.com/kexp160.aac"))
        assertEquals("radio@3", c)
        assertEquals(listOf("2", "3"), s4.extraAccounts["radio"])
        assertEquals(listOf("spotify", "radio", "radio@2", "radio@3"), s4.connectors)
        // A colour that is no colour is not kept; a name equal to the station's is no name of its own.
        val (s5, d) = RadioConnectors.add(s4, RadioStation("NTS", "https://stream-relay-geo.ntslive.net/stream"), name = "NTS", color = "sparkly")
        assertNull(s5.connectorNames[d])
        assertNull(s5.connectorColors[d])
        // Taken away: the slot, the station, the name and the colour go; the others stay.
        val gone = RadioConnectors.remove(s5, "radio@2")
        assertEquals(listOf("3", "4"), gone.extraAccounts["radio"])
        assertFalse("radio@2" in gone.radioStations)
        assertFalse("radio@2" in gone.connectors)
        val main = RadioConnectors.remove(gone, "radio")
        assertNull(main.connectorNames["radio"])
        assertEquals(listOf("3", "4"), main.extraAccounts["radio"])
        // The main slot is free again for the next station.
        assertEquals("radio", RadioConnectors.add(main, RadioStation("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3")).second)
    }

    @Test fun aSyncThatIsNoLongerARadioOrShazamLeavesItsConnector() {
        val store = FileStore(Files.createTempDirectory("radio").toFile())
        store.update { StoreData(jobs = listOf(radioJob("a", "Radio 105", r105), SyncJob("s", "s", PlaylistRef("spotify", "x"), PlaylistRef("ytm"), shazam = true))) }
        assertEquals(listOf("radio", "shazam"), store.data.jobs.map { it.via })
        store.update { d -> d.copy(jobs = d.jobs.map { it.copy(radio = null, shazam = false) }) }
        assertEquals(listOf(null, null), store.data.jobs.map { it.via })
        // The connectors stay in Accounts, as any account does when its syncs go.
        assertTrue(store.data.settings.connectors.containsAll(listOf("radio", "shazam")))
    }

    @Test fun aNewSyncFromARadioConnectorIsARadioSyncAsBefore() = runBlocking {
        val env = ContractEnv()
        env.store.updateSettings { RadioConnectors.add(it, RadioStation("Radio 105", r105, "https://www.105.net")).first }
        val job = RadioConnectors.newSync(env, "radio", PlaylistRef("spotify", null, "105"), "n1", days = listOf(5, 1, 9), startMinute = 420, endMinute = 540, wifiOnly = true)!!
        assertEquals(RadioSource("Radio 105", r105, "https://www.105.net", listOf(1, 5), 420, 540), job.radio)
        assertEquals("radio", job.via)
        assertEquals(LocalFilesProvider.id, job.source.provider)
        assertEquals("Radio - Radio 105", job.source.playlistName)
        assertTrue(File(LocalFilesProvider.dir(env), "${job.source.playlistId}.csv").exists())
        assertEquals("Radio 105 radio", job.name)
        assertTrue(job.wifiOnly)
        assertNull(RadioConnectors.newSync(env, "radio@9", PlaylistRef("spotify"), "n2"))
        assertNull(RadioConnectors.newSync(env, "spotify", PlaylistRef("spotify"), "n3"))
        // Saved, it is already where the store puts it.
        env.store.upsertJob(job)
        assertEquals(job, env.store.job("n1"))
    }

    @Test fun theConnectorsReadTheSongsHeardAndAreInRadioAndCharts() = runBlocking {
        val env = ContractEnv()
        Providers.install(Providers.engineFactories(), singletons = listOf(LocalFilesProvider, ShazamProvider))
        try {
            env.store.updateSettings { RadioConnectors.add(RadioConnectors.add(it, RadioStation("Virgin Radio", virgin)).first, RadioStation("Radio 105", r105)).first }
            val job = RadioConnectors.newSync(env, "radio@2", PlaylistRef("spotify"), "n1")!!
            env.store.upsertJob(job)
            LocalFilesProvider.addTracks(env, job.source.playlistId!!, listOf(com.xlollx.songport.model.Track("x", "Beggin'", listOf("Måneskin"))))
            val p = Providers.byId("radio@2") as RadioProvider
            assertEquals("Radio 105", p.label(env))
            assertEquals(listOf(job.source.playlistId), p.playlists(env).map { it.id })
            assertEquals(listOf("Beggin'"), p.tracks(env, job.source.playlistId!!).map { it.title })
            assertFalse(p.canWrite)
            assertTrue(p.supportsMultipleAccounts)
            assertEquals(ServiceCatalog.Category.RADIO, p.category)
            assertEquals(ServiceCatalog.Category.RADIO, ShazamProvider.category)
            assertFalse(ShazamProvider.supportsMultipleAccounts)
            assertNotNull(Providers.byId("shazam"))
            assertTrue(Providers.services().any { it.id == "radio" })
            assertEquals(listOf("radio", "radio@2"), Providers.accountsOf("radio").map { it.id })
        } finally {
            Providers.install(emptyList())
        }
    }
}
