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
}
