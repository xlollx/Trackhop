package com.xlollx.songport.providers

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.platform.BuildDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every sign-in asks which account, the first account's too (#64: a new sign-in entered in silence
 * with whatever account the browser held). The services with a parameter for it get it on every
 * slot; TIDAL and Deezer have none and are left as they are.
 */
class AccountChoiceTest {
    private val env = ContractEnv()
    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    @Test fun spotifyShowsItsDialogForThePrimaryAccountToo() {
        BuildDefaults.values = mapOf("SPOTIFY_CLIENT_ID" to "cid123")
        for (slot in listOf("", "2")) {
            val q = query(SpotifyProvider(slot).authUrl(env, "state", "challenge"))
            assertEquals("slot '$slot'", "true", q["show_dialog"])
            assertEquals("cid123", q["client_id"])
        }
    }

    @Test fun googleShowsItsAccountChooserForThePrimaryAccountToo() {
        BuildDefaults.values = mapOf("GOOGLE_CLIENT_ID" to "gcid.apps.googleusercontent.com")
        for (slot in listOf("", "2")) {
            val q = query(YouTubeProvider(slot).authUrl(env, "state", "challenge"))
            assertEquals("slot '$slot'", "consent select_account", q["prompt"])
            assertEquals("offline", q["access_type"])
        }
    }

    @Test fun tidalHasNoSuchParameterAndIsLeftAsItIs() {
        BuildDefaults.values = mapOf("TIDAL_CLIENT_ID" to "tid")
        val q = query(TidalProvider().authUrl(env, "state", "challenge"))
        assertFalse(q.containsKey("prompt"))
        assertFalse(q.containsKey("show_dialog"))
        assertTrue(q.containsKey("code_challenge"))
    }
}
