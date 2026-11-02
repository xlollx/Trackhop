package com.xlollx.songport.server

import com.xlollx.songport.ytmbridge.LiveOneWebClient
import com.xlollx.songport.ytmbridge.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** LiveOne's web player, read offline: its keys, the pasted token, the presets and the search. */
class LiveOneTest {
    /** How the player writes a key: a function in hex (three digits a character) returning a string in hex. */
    private fun encode(value: String): String {
        fun hex3(s: String) = s.map { "%03x".format(it.code) }.joinToString("")
        val js = "function decodeFromHex(str) {var str    = \"${hex3(value)}\";var returnVal = \"\";return returnVal;}"
        return hex3(js)
    }

    @Test fun readsTheKeysOutOfThePlayersScript() {
        // The web player's config object, then another device's key assigned later (Fire TV): the first one counts.
        val script = "x;slackerConfig={client:1,tokenClientId:45,K:\"${encode("sign-key")}\",z:2,OAUTH_K:\"${encode("oauth-key-123")}\"};" +
            "CONFIG.slackerConfig.OAUTH_K=\"${encode("firetv-key")}\""
        val p = LiveOneWebClient.parsePlayer("4.0.1426", script)!!
        assertEquals("sign-key", p.k)
        assertEquals("oauth-key-123", p.oauthK)
        assertNull(LiveOneWebClient.parsePlayer("1", "nothing here"))
    }

    @Test fun takesTheTokenAloneOrInsideTheUserEntry() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"accountId":653700473,"user_name":"x"}""".toByteArray())
        val jwt = "eyJhbGciOiJIUzI1NiJ9.$payload.sig-nature_1"
        assertEquals(jwt, LiveOneWebClient.tokenIn("  \"$jwt\" "))
        assertEquals(jwt, LiveOneWebClient.tokenIn("""{"id":"653700473","access_token":"eyJa.b.c","refresh_token":"$jwt"}"""))
        assertNull(LiveOneWebClient.tokenIn("sp_dc=abc"))
        assertEquals("653700473", LiveOneWebClient.accountOf(jwt))
    }

    @Test fun signsAsThePlayerDoes() {
        // sha1 of the key followed by the data, in lower-case hex.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", LiveOneWebClient.sha1("abc"))
    }

    @Test fun readsThePresetsAndTheSearch() {
        val presets = parseJson(
            """{"content":[{"item":{"objectId":"song:46643486","songId":46643486,"songName":"Fighters","track":{"length":226,
            "artists":[{"artist":{"artistName":"Conro"}}],"artist":{"artistName":"Conro"},"album":{"albumName":"Fighters"}}},"type":"preset"}]}""",
        )
        val t = LiveOneWebClient.parsePresets(presets).single()
        assertEquals("46643486", t.id); assertEquals("Fighters", t.title); assertEquals(listOf("Conro"), t.artists); assertEquals(226_000, t.durationMs)
        val found = LiveOneWebClient.parseSearch("""{"results":[{"type":"song","id":88358679,"name":"Pomegranate","artistName":"Deadmau5","albumName":"Pomegranate"}]}""")
        assertEquals("88358679", found.single().id)
    }
}
