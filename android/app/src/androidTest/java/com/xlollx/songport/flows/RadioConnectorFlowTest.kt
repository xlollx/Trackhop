package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.RadioStation
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.RadioConnectors
import com.xlollx.songport.sync.RadioPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Radio stations as accounts (2.2): two stations in Accounts, each a card with its name and station;
 * in the editor each is a source tile, and picked it makes the radio sync it always was, on its connector.
 */
@RunWith(AndroidJUnit4::class)
class RadioConnectorFlowTest : FlowTest() {
    private companion object {
        const val VIRGIN = "http://stream.virgin.example/live.mp3"
        const val R105 = "http://stream.105.example/live.mp3"
    }

    override fun baseSettings(): Settings = super.baseSettings().let { s ->
        RadioConnectors.add(RadioConnectors.add(s, RadioStation("Virgin Radio Italia", VIRGIN), name = "Virgin", color = "red").first, RadioStation("Radio 105", R105)).first
    }

    @After
    fun stopListening() {
        store.update { StoreData(settings = super.baseSettings()) }
        RadioPlan.refresh(app, start = false)
    }

    @Test
    fun twoStationsAreAccountsAndOneIsTheSourceOfANewSync() {
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText("Virgin")
        waitText("Radio 105")
        waitText("Virgin Radio Italia", substring = true)

        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        newSync()
        waitText(s(R.string.editor_title_new))
        openSourcePicker()
        // The stations are tiles like the other accounts; the old "Radio" tile is gone with them there.
        clickTile("Radio 105")
        waitText(s(R.string.radio_days))
        assertFalse(exists(s(R.string.editor_mirror)))
        chooseTarget("Deezer")
        click(s(R.string.save))

        waitFor("the radio sync saved") { store.data.jobs.size == 1 }
        val job = store.data.jobs.single()
        assertEquals("radio@2", job.via)
        assertEquals("Radio 105", job.radio!!.station)
        assertEquals(R105, job.radio!!.stream)
        assertEquals(LocalFilesProvider.id, job.source.provider)
        assertEquals("deezer", job.target.provider)
        assertTrue("radio syncs listen on Wi-Fi by default", job.wifiOnly)
        // In use: the station's account stays.
        assertEquals(1, RadioConnectors.syncsOf(store.data.jobs, "radio@2").size)
        assertTrue("radio@2" in store.data.settings.connectors)
    }
}
