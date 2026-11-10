package com.xlollx.songport.sync

import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Label
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {
    private fun job(id: String, color: String? = null, label: String? = null, tags: Map<String, String> = emptyMap(), favorite: Boolean = false) =
        SyncJob(id = id, name = id, source = PlaylistRef("spotify", "s"), target = PlaylistRef("ytm", "t"), color = color, label = label, tags = tags, favorite = favorite)

    // ------------------------------------------------------------ the colours of before labels

    @Test fun coloursOfBeforeLabelsAreKept() {
        // What 1.16 wrote: a ready colour, a colour of one's own, no colour.
        val old = StoreData(jobs = listOf(job("a", "teal"), job("b", "#FF8800"), job("c")))
        val d = Labels.normalized(old)
        // The colours are what they were.
        assertEquals(listOf("teal", "#FF8800", null), d.jobs.map { it.color })
        // A ready colour is its own label; a colour of one's own is free; no colour, no label.
        assertEquals(listOf("teal", null, null), d.jobs.map { it.label })
        assertTrue(d.settings.labels.isEmpty())
        // Done once: nothing changes the second time.
        assertSame(d, Labels.normalized(d))
    }

    @Test fun aStoreFileOfBeforeLabelsStillReads() {
        val text = """{"jobs":[{"id":"a","name":"A","source":{"provider":"spotify","playlistId":"1"},"target":{"provider":"ytm"},"color":"pink","accentStyle":"edge"},
            {"id":"b","name":"B","source":{"provider":"spotify","playlistId":"2"},"target":{"provider":"ytm"},"color":"#12AB34"}],"settings":{"cardAccent":"glow"}}"""
        val d = Labels.normalized(Json { ignoreUnknownKeys = true }.decodeFromString(StoreData.serializer(), text))
        assertEquals(listOf("pink", "#12AB34"), d.jobs.map { it.color })
        assertEquals(listOf("pink", null), d.jobs.map { it.label })
        assertEquals("edge", d.jobs[0].accentStyle)
        assertFalse(d.jobs[0].favorite)
        assertTrue(d.jobs[0].tags.isEmpty())
        assertEquals("glow", d.settings.cardAccent)
    }

    @Test fun aConfigurationFileOfBeforeLabelsStillReads() {
        val text = """{"format":"trackhop-config","version":1,"writtenBy":"Trackhop 1.16.0 (Android)","jobs":[
            {"id":"a","name":"A","source":{"provider":"spotify","playlistId":"1"},"target":{"provider":"ytm"},"color":"red"}],"settings":{}}"""
        val s = ConfigBackup.read(text)
        assertEquals("red", s.jobs.single().color)
        assertNull(s.jobs.single().label)
        assertTrue(s.settings.labels.isEmpty())
    }

    /** The whole way: a store file and a configuration file written by 1.16, read by this one. */
    @Test fun anOldStoreAndAnOldBackupComeInWithTheirColours() {
        val dir = java.nio.file.Files.createTempDirectory("labels").toFile()
        java.io.File(dir, "store.json").writeText(
            """{"jobs":[{"id":"a","name":"A","source":{"provider":"spotify","playlistId":"1"},"target":{"provider":"ytm"},"color":"pink"},
            {"id":"b","name":"B","source":{"provider":"spotify","playlistId":"2"},"target":{"provider":"ytm"},"color":"#12AB34"}]}""",
        )
        val store = com.xlollx.songport.data.FileStore(dir)
        assertEquals(listOf("pink", "#12AB34"), store.data.jobs.map { it.color })
        assertEquals(listOf("pink", null), store.data.jobs.map { it.label })

        val env = com.xlollx.songport.contract.ContractEnv()
        // This device has a label of its own, worn by a sync: an old file must not take it away.
        env.store.update { Labels.save(it.copy(jobs = listOf(job("mine", "#0000FF"))), "#0000FF", "Mine") { "l-1" }.first }
        val old = ConfigBackup.read(
            """{"format":"trackhop-config","version":1,"writtenBy":"Trackhop 1.16.0 (Android)","jobs":[
            {"id":"x","name":"X","source":{"provider":"spotify","playlistId":"1"},"target":{"provider":"ytm"},"color":"red","accentStyle":"edge"},
            {"id":"y","name":"Y","source":{"provider":"spotify","playlistId":"2"},"target":{"provider":"ytm"},"color":"#ABCDEF"},
            {"id":"z","name":"Z","source":{"provider":"spotify","playlistId":"3"},"target":{"provider":"ytm"}}],"settings":{"cardAccent":"glow"}}""",
        )
        assertEquals(3, ConfigBackup.import(env, old).syncs)
        val jobs = env.store.data.jobs.associateBy { it.id }
        assertEquals(listOf("red", "#ABCDEF", null, "#0000FF"), listOf("x", "y", "z", "mine").map { jobs[it]!!.color })
        assertEquals(listOf("red", null, null, "l-1"), listOf("x", "y", "z", "mine").map { jobs[it]!!.label })
        assertEquals("edge", jobs["x"]!!.accentStyle)
        assertEquals(listOf(Label("l-1", "#0000FF", "Mine")), env.store.data.settings.labels)

        // And a file written now carries labels, tags and stars to another device.
        env.store.update { d -> d.copy(jobs = d.jobs.map { if (it.id == "x") it.copy(favorite = true, tags = mapOf("genre" to "metal", "kids" to "")) else it }) }
        env.store.update { Labels.save(it, "red", "Loud").first }
        val other = com.xlollx.songport.contract.ContractEnv()
        ConfigBackup.import(other, ConfigBackup.read(ConfigBackup.export(env, "test")))
        val x = other.store.job("x")!!
        assertTrue(x.favorite)
        assertEquals(mapOf("genre" to "metal", "kids" to ""), x.tags)
        assertEquals("Loud", Labels.name(x, other.store.data.settings.labels))
        assertEquals("Mine", Labels.name(other.store.job("mine")!!, other.store.data.settings.labels))
        assertEquals("#0000FF", other.store.job("mine")!!.color)
    }

    /** Whatever writes a sync, the store keeps it in order: the label's colour, a ready colour as its label. */
    @Test fun theStoreKeepsSyncsInOrder() {
        val store = com.xlollx.songport.data.FileStore(java.nio.file.Files.createTempDirectory("labels").toFile())
        store.update { it.copy(jobs = listOf(job("a", "teal"), job("b", "#000000", label = "gone"))) }
        assertEquals(listOf("teal", null), store.data.jobs.map { it.label })
        store.upsertJob(job("c", "red", tags = mapOf(" Genre " to " Metal ")))
        assertEquals("red", store.job("c")!!.label)
        assertEquals(mapOf("Genre" to "Metal"), store.job("c")!!.tags)
    }

    @Test fun aLabelThatIsGoneLeavesTheColour() {
        val j = Labels.normalized(job("a", "#102030", label = "l-gone"), emptyList())
        assertEquals("#102030", j.color)
        assertNull(j.label)
    }

    @Test fun theLabelGivesTheColour() {
        val labels = listOf(Label("l-1", "#AA00BB", "Gym"))
        assertEquals("#AA00BB", Labels.normalized(job("a", "red", label = "l-1"), labels).color)
        assertEquals("teal", Labels.normalized(job("a", "#000000", label = "teal"), labels).color)
    }

    // ------------------------------------------------------------ naming, recolouring, deleting

    @Test fun namingAReadyColour() {
        val (d, id) = Labels.save(Labels.normalized(StoreData(jobs = listOf(job("a", "teal"), job("b", "red")))), "teal", " Gym ")
        assertEquals("teal", id)
        assertEquals(listOf(Label("teal", "teal", "Gym")), d.settings.labels)
        assertEquals("Gym", Labels.name(d.jobs[0], d.settings.labels))
        assertEquals("", Labels.name(d.jobs[1], d.settings.labels))
        // Deleting it takes the name away, not the colour.
        val gone = Labels.delete(d, "teal")
        assertTrue(gone.settings.labels.isEmpty())
        assertEquals("teal", gone.jobs[0].color)
        assertEquals("teal", gone.jobs[0].label)
    }

    @Test fun savingAColourOfOnesOwnRenamingRecolouringDeleting() {
        val start = Labels.normalized(StoreData(jobs = listOf(job("a", "#ff8800"), job("b", "#FF8800"), job("c", "#000001"))))
        val (d, id) = Labels.save(start, "#FF8800", "From Spotify") { "l-1" }
        assertEquals("l-1", id)
        // Every sync with that exact colour wears the label.
        assertEquals(listOf("l-1", "l-1", null), d.jobs.map { it.label })
        // Saving the same colour again renames the label instead of making a second one.
        val (again, id2) = Labels.save(d, "#ff8800", "Spotify") { "l-2" }
        assertEquals("l-1", id2)
        assertEquals(listOf(Label("l-1", "#FF8800", "Spotify")), again.settings.labels)

        val renamed = Labels.rename(d, "l-1", "Mine")
        assertEquals("Mine", Labels.name(renamed.jobs[0], renamed.settings.labels))

        val recoloured = Labels.recolor(renamed, "l-1", "#00aa11")
        assertEquals(listOf("#00AA11", "#00AA11", "#000001"), recoloured.jobs.map { it.color })
        // Not a colour: nothing changes.
        assertSame(recoloured, Labels.recolor(recoloured, "l-1", "green-ish"))

        val deleted = Labels.delete(recoloured, "l-1")
        assertTrue(deleted.settings.labels.isEmpty())
        assertEquals(listOf("#00AA11", "#00AA11", "#000001"), deleted.jobs.map { it.color })
        assertEquals(listOf(null, null, null), deleted.jobs.map { it.label })
        // An empty name deletes too.
        assertTrue(Labels.rename(recoloured, "l-1", "  ").settings.labels.isEmpty())
        // A colour of one's own needs a name to become a label.
        assertNull(Labels.save(start, "#FF8800", " ").second)
    }

    @Test fun findingALabelByIdKeyOrName() {
        val labels = listOf(Label("l-1", "#AA00BB", "Palestra è"), Label("teal", "teal", "Calm"))
        assertEquals("l-1", Labels.resolve(labels, "l-1")?.id)
        assertEquals("l-1", Labels.resolve(labels, "PALESTRA E")?.id)
        assertEquals("teal", Labels.resolve(labels, "calm")?.id)
        assertEquals("red", Labels.resolve(labels, "Red")?.id)
        assertNull(Labels.resolve(labels, "nope"))
    }

    // ------------------------------------------------------------ tags

    @Test fun tagsAreTidied() {
        val t = Labels.cleanTags(linkedMapOf(" Genre " to "  Heavy   Metal ", "genre" to "pop", "" to "x", "a:b=c" to "", "mood" to "\"gym\""))
        assertEquals(linkedMapOf("Genre" to "Heavy Metal", "a b c" to "", "mood" to "gym"), t)
        val clean = mapOf("genre" to "metal", "kids" to "")
        assertSame(clean, Labels.cleanTags(clean))
        assertEquals(Labels.MAX_TAGS, Labels.cleanTags((1..40).associate { "k$it" to "v" }).size)
    }

    @Test fun parsingATag() {
        assertEquals("genre" to "metal", Labels.parseTag("genre:metal"))
        assertEquals("genre" to "metal", Labels.parseTag(" genre = metal "))
        assertEquals("genre" to null, Labels.parseTag("genre"))
        assertEquals("genre" to null, Labels.parseTag("genre:"))
        assertEquals("genre" to "heavy metal", Labels.parseTag("genre:\"heavy metal\""))
        assertEquals("my key" to "a=b", Labels.parseTag("\"my key\"=a=b"))
        assertNull(Labels.parseTag(":metal"))
        assertNull(Labels.parseTag("  "))
    }

    private val jobs = listOf(
        job("a", "teal", "teal", mapOf("Genre" to "Metal", "mood" to "gym"), favorite = true),
        job("b", "#AA00BB", "l-1", mapOf("genre" to "Heavy Metal", "kids" to "")),
        job("c", tags = mapOf("genre" to "pop")),
        job("d"),
    )
    private val labels = listOf(Label("l-1", "#AA00BB", "Gym"))
    private fun ids(tags: List<String> = emptyList(), label: String? = null, favorite: Boolean? = null) =
        Labels.select(jobs, labels, tags, label, favorite).joinToString("") { it.id }

    @Test fun selectingByTagLabelAndStar() {
        assertEquals("abcd", ids())
        assertEquals("a", ids(listOf("genre:metal")))
        assertEquals("a", ids(listOf("GENRE=métal")))
        assertEquals("abc", ids(listOf("genre")))
        assertEquals("b", ids(listOf("kids")))
        assertEquals("b", ids(listOf("genre:\"heavy metal\"")))
        // Every tag asked for.
        assertEquals("a", ids(listOf("genre", "mood:gym")))
        assertEquals("", ids(listOf("genre:metal", "kids")))
        assertEquals("b", ids(label = "gym"))
        assertEquals("b", ids(label = "l-1"))
        assertEquals("a", ids(label = "teal"))
        assertEquals("", ids(label = "nope"))
        assertEquals("a", ids(favorite = true))
        assertEquals("bcd", ids(favorite = false))
        assertEquals("", ids(listOf("genre"), label = "gym", favorite = true))
        assertThrows(IllegalArgumentException::class.java) { ids(listOf("=metal")) }
    }

    @Test fun suggestionsFromWhatIsUsed() {
        assertEquals(listOf("Genre", "kids", "mood"), Labels.keysInUse(jobs))
        assertEquals(listOf("Heavy Metal", "Metal", "pop"), Labels.valuesInUse(jobs, "GENRE"))
        assertTrue(Labels.valuesInUse(jobs, "kids").isEmpty())
    }

    @Test fun favouritesGoFirstTheRestKeepTheirOrder() {
        val list = listOf(job("a"), job("b", favorite = true), job("c"), job("d", favorite = true))
        assertEquals("bdac", Labels.favoritesFirst(list).joinToString("") { it.id })
        val none = listOf(job("a"), job("b"))
        assertSame(none, Labels.favoritesFirst(none))
    }

    @Test fun theStyleDialogSaves() {
        val start = Labels.normalized(StoreData(jobs = listOf(job("a", "teal"), job("b"), job("c", "#010203"))))
        // A ready colour with a name.
        var d = Labels.styled(start, setOf("b"), "red", "Loud")
        assertEquals("red", d.jobs[1].color); assertEquals("red", d.jobs[1].label)
        assertEquals("Loud", Labels.name(d.jobs[1], d.settings.labels))
        // A colour of one's own without a name: free, no label.
        d = Labels.styled(d, setOf("b"), "#0A0B0C", "")
        assertEquals("#0A0B0C", d.jobs[1].color); assertNull(d.jobs[1].label)
        // With a name: a label, which another sync can then pick by its id.
        d = Labels.styled(d, setOf("b"), "#0A0B0C", "Mine") { "l-9" }
        assertEquals("l-9", d.jobs[1].label)
        d = Labels.styled(d, setOf("a"), "l-9", "Mine")
        assertEquals("#0A0B0C", d.jobs[0].color); assertEquals("l-9", d.jobs[0].label)
        // Recoloured and renamed: both follow.
        d = Labels.styled(d, setOf("a"), "l-9", "Ours", "#112233")
        assertEquals(listOf("#112233", "#112233", "#010203"), d.jobs.map { it.color })
        assertEquals(listOf(Label("red", "red", "Loud"), Label("l-9", "#112233", "Ours")), d.settings.labels)
        // The name cleared: the label goes, the colour stays on both.
        d = Labels.styled(d, setOf("a"), "l-9", "")
        assertEquals(listOf("#112233", "#112233", "#010203"), d.jobs.map { it.color })
        assertEquals(listOf(null, null, null), d.jobs.map { it.label })
        // No colour.
        d = Labels.styled(d, setOf("a", "c"), null, "ignored")
        assertEquals(listOf(null, "#112233", null), d.jobs.map { it.color })
    }

    @Test fun movingInTheListAsShown() {
        // Shown: b d a c (starred first).
        val list = listOf(job("a"), job("b", favorite = true), job("c"), job("d", favorite = true))
        fun moved(id: String, to: Int): String {
            val at = Labels.storeIndex(list, id, to)
            val rest = list.filter { it.id != id }
            return Labels.favoritesFirst(rest.take(at) + list.first { it.id == id } + rest.drop(at)).joinToString("") { it.id }
        }
        assertEquals("dbac", moved("d", 0))
        assertEquals("bdca", moved("a", 3))
        assertEquals("bdca", moved("c", 2))
        // Dropped among the starred: the first of the others.
        assertEquals("bdca", moved("c", 0))
        // A starred one dropped at the end: the last of the starred.
        assertEquals("dbac", moved("b", 3))
        // Without stars it is the plain position.
        val plain = listOf(job("a"), job("b"), job("c"))
        assertEquals(2, Labels.storeIndex(plain, "a", 2))
        assertEquals(0, Labels.storeIndex(plain, "c", 0))
    }

    @Test fun settingsKeepTheirLabelsInAConfigurationFile() {
        val s = Settings(labels = labels)
        val json = Json { encodeDefaults = true }
        assertEquals(labels, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), s)).labels)
    }
}
