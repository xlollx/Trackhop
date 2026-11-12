package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.providers.EmbyProvider
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.sync.Covers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A playlist's picture on the personal servers, as their documented APIs take it: Jellyfin and Emby
 * the base64 of the JPEG on the item's Primary image, Plex the JPEG itself as a poster. Made-up
 * addresses and tokens; the image is a few bytes that start like a JPEG.
 */
class PlaylistCoversContractTest : ContractTest() {
    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

    private fun signIn(id: String, url: String) =
        env.tokens.set(id, Tokens(accessToken = "server-token", userId = "user-1", userName = "me", extra = mapOf("url" to url)))

    @Test fun jellyfinTakesTheBase64OfTheJpegAsThePrimaryImage() = runBlocking {
        val p = JellyfinProvider()
        signIn(p.id, "https://jf.example.com")
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        assertTrue(p.canSetCover)
        p.setPlaylistCover(env, "pl-1", jpeg)
        val r = next()
        assertCall(r, "POST", "jf.example.com", "/Items/pl-1/Images/Primary")
        assertEquals("image/jpeg", r.getHeader("Content-Type")!!.substringBefore(';'))
        assertTrue(r.getHeader("Authorization")!!.contains("Token=\"server-token\""))
        assertEquals("/9j/4AECAw==", r.bodyText())
        noMoreRequests()
    }

    @Test fun embyTakesItUnderItsOwnRootAndHeaders() = runBlocking {
        val p = EmbyProvider()
        signIn(p.id, "https://emby.example.com")
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        p.setPlaylistCover(env, "pl-2", jpeg)
        val r = next()
        assertCall(r, "POST", "emby.example.com", "/emby/Items/pl-2/Images/Primary")
        assertEquals("server-token", r.getHeader("X-Emby-Token"))
        assertEquals("image/jpeg", r.getHeader("Content-Type")!!.substringBefore(';'))
        assertEquals("/9j/4AECAw==", r.bodyText())
    }

    @Test fun aRefusedCoverIsAnErrorThatSaysWhy() = runBlocking {
        val p = JellyfinProvider()
        signIn(p.id, "https://jf.example.com")
        json("""{"error":"forbidden"}""", 403)
        try { p.setPlaylistCover(env, "pl-1", jpeg); fail() } catch (e: ProviderException) {
            assertTrue(e.message!!.contains("403"))
            assertTrue(e.message!!.contains("administrator"))
        }
    }

    @Test fun plexTakesTheJpegItselfAsAPoster() = runBlocking {
        val p = PlexProvider()
        signIn(p.id, "https://plex.example.com:32400")
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200))
        assertTrue(p.canSetCover)
        p.setPlaylistCover(env, "4242", jpeg)
        val r = next()
        assertCall(r, "POST", "plex.example.com", "/library/metadata/4242/posters")
        assertEquals("server-token", r.getHeader("X-Plex-Token"))
        assertEquals("image/jpeg", r.getHeader("Content-Type")!!.substringBefore(';'))
        assertEquals(jpeg.toList(), r.body.clone().readByteArray().toList())
        noMoreRequests()
    }

    @Test fun theCoverIsTheCentreSquareWithinTheSizesEveryServiceTakes() {
        // A wide image loses its sides, a tall one its top and bottom; the side stays between the limits.
        assertEquals(Covers.Square(100, 0, 600, 600), Covers.square(800, 600, 360, 640))
        assertEquals(Covers.Square(0, 200, 1000, 640), Covers.square(1000, 1400, 360, 640))
        // Smaller than YouTube Music's minimum: enlarged to it.
        assertEquals(Covers.Square(0, 0, 300, 360), Covers.square(300, 300, 360, 640))
        // 256 KB once base64-encoded, Spotify's limit.
        assertTrue((Covers.MAX_BYTES + 2) / 3 * 4 <= 256 * 1024)
    }
}
