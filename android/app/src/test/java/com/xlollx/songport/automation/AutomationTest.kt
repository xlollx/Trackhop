package com.xlollx.songport.automation

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A sync named by an automation app: by its id, or its name whatever the case. */
class AutomationTest {
    private fun job(id: String, name: String) = SyncJob(id = id, name = name, source = PlaylistRef("a", "p", "p"), target = PlaylistRef("b", "t", "t"))
    private val jobs = listOf(job("1", "Metal & More"), job("2", "Rap"), job("Rap", "Other"))

    @Test fun byIdFirstThenByName() {
        assertEquals("1", Automation.find(jobs, "1")?.id)
        assertEquals("1", Automation.find(jobs, " metal & more ")?.id)
        // An id wins over another sync's name.
        assertEquals("Rap", Automation.find(jobs, "Rap")?.id)
        assertNull(Automation.find(jobs, "Jazz"))
    }

    @Test fun aGroupByTagOrLabelTurnedOnOnly() {
        val data = com.xlollx.songport.data.StoreData(
            jobs = listOf(
                job("1", "A").copy(tags = mapOf("Genre" to "Metal")),
                job("2", "B").copy(tags = mapOf("genre" to "metal"), enabled = false),
                job("3", "C").copy(tags = mapOf("gym" to ""), label = "teal", color = "teal"),
            ),
            settings = com.xlollx.songport.data.Settings(labels = listOf(com.xlollx.songport.model.Label("teal", "teal", "Gym"))),
        )
        assertEquals(listOf("1"), Automation.group(data, "genre=metal", null).map { it.id })
        assertEquals(listOf("3"), Automation.group(data, "gym", null).map { it.id })
        assertEquals(listOf("3"), Automation.group(data, null, "gym").map { it.id })
        assertEquals(emptyList<String>(), Automation.group(data, "genre", "gym").map { it.id })
        // A tag without a key starts nothing, rather than everything.
        assertEquals(emptyList<String>(), Automation.group(data, "=metal", null).map { it.id })
    }
}
