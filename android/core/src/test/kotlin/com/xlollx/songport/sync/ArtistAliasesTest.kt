package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections

/** An artist's other names from MusicBrainz (#81), against a stand-in for musicbrainz.org. */
class ArtistAliasesTest {
    private val queries: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var status = 200
    private var body: (String) -> String = { """{"artists":[]}""" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val q = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty().substringAfter("query=").substringBefore('&'), "UTF-8")
            queries += q
            val bytes = body(q).toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = 1_700_000_000_000L

    private val jayChou = """{"artists":[{"id":"a","name":"周杰倫","sort-name":"Chou, Jay","score":100,
        "aliases":[{"name":"Jay Chou","sort-name":"Chou, Jay"},{"name":"周杰伦","sort-name":"周杰伦"},{"name":"Zhou Jielun"}]},
        {"id":"b","name":"Jay Chou Tribute","sort-name":"Jay Chou Tribute","score":60,"aliases":[]}]}"""

    @Before fun setUp() {
        MusicBrainz.base = "http://127.0.0.1:${server.address.port}/ws/2"
        MusicBrainz.now = { clock }
        MusicBrainz.pace = Pacer(0)
        MusicBrainz.reset()
        ArtistAliases.reset()
    }

    @After fun tearDown() {
        server.stop(0)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.now = { System.currentTimeMillis() }
        MusicBrainz.pace = Pacer(1100)
        MusicBrainz.reset()
        ArtistAliases.reset()
    }

    private fun t(title: String, artist: String, dur: Long = 269_000) = Track(id = title + artist, title = title, artists = listOf(artist), durationMs = dur)

    @Test fun aChineseArtistLearnsItsLatinNameOnceAndTheSongsMeet() = runBlocking {
        body = { jayChou }
        val src = t("晴天", "周杰倫")
        val cand = t("晴天", "Jay Chou")
        assertTrue(Matcher.score(src, cand) < Matcher.DEFAULT_THRESHOLD)
        ArtistAliases.learnFor(env, src)
        ArtistAliases.learnFor(env, src, listOf(cand))
        assertEquals(1, queries.size)
        assertEquals("artist:\"周杰倫\" OR alias:\"周杰倫\"", queries.single())
        assertTrue(Matcher.score(src, cand) >= Matcher.REVIEW_THRESHOLD)
        // The simplified spelling and the pinyin too; the sort name read the right way round.
        assertTrue(Matcher.score(t("晴天", "周杰伦"), t("晴天", "Zhou Jielun")) >= Matcher.REVIEW_THRESHOLD)
        assertEquals("Jay Chou", ArtistAliases.namesOf("周杰倫").first())
    }

    @Test fun aLatinArtistAsksOnlyWhenACandidateOfTheSameTitleIsInAnotherScript() = runBlocking {
        body = { jayChou.replace("\"name\":\"周杰倫\"", "\"name\":\"Jay Chou\"").replace("{\"name\":\"Jay Chou\",\"sort-name\":\"Chou, Jay\"}", "{\"name\":\"周杰倫\"}") }
        val src = t("晴天", "Jay Chou")
        ArtistAliases.learnFor(env, src, listOf(t("Sunny Day", "周杰倫"), t("Other", "Jay Chou")))
        assertEquals(0, queries.size)
        ArtistAliases.learnFor(env, src, listOf(t("晴天", "周杰倫")))
        assertEquals(1, queries.size)
        assertTrue(Matcher.score(src, t("晴天", "周杰倫")) >= Matcher.REVIEW_THRESHOLD)
        // Podcasts and albums never ask.
        ArtistAliases.reset()
        ArtistAliases.learnFor(env, src.copy(kind = Track.KIND_ALBUM, artists = listOf("五月天")))
        assertEquals(1, queries.size)
    }

    @Test fun theAnswerIsRememberedAndAskedAgainOnlyAfterItExpires() = runBlocking {
        body = { jayChou }
        ArtistAliases.learn(env, "周杰倫")
        ArtistAliases.reset()
        ArtistAliases.learn(env, "周杰倫")
        assertEquals(1, queries.size)
        assertTrue(ArtistAliases.same("周杰伦", "jay chou"))
        // Nothing found is remembered a month.
        ArtistAliases.reset()
        body = { """{"artists":[]}""" }
        ArtistAliases.learn(env, "五月天")
        ArtistAliases.reset()
        ArtistAliases.learn(env, "五月天")
        assertEquals(2, queries.size)
        clock += 31L * 24 * 3_600_000
        ArtistAliases.reset()
        ArtistAliases.learn(env, "五月天")
        assertEquals(3, queries.size)
    }

    @Test fun aNameSeveralArtistsAnswerToTeachesNothing() = runBlocking {
        // "Mayday" is more than one band: one name for two would make them one.
        body = { """{"artists":[{"id":"a","name":"Mayday","score":100,"aliases":[{"name":"五月天"}]},
            {"id":"b","name":"Mayday","score":100,"aliases":[{"name":"Mayday (US band)"}]}]}""" }
        ArtistAliases.learn(env, "Mayday")
        assertFalse(ArtistAliases.same("mayday", "五月天"))
        // A low score or a name that is not the one asked for teaches nothing either.
        ArtistAliases.reset()
        body = { """{"artists":[{"id":"a","name":"Maydays","score":80,"aliases":[{"name":"x"}]}]}""" }
        ArtistAliases.learn(env, "Mayday")
        assertFalse(ArtistAliases.same("mayday", "maydays"))
    }

    @Test fun aBusyMusicBrainzIsNotRememberedAsNothing() = runBlocking {
        status = 503
        ArtistAliases.learn(env, "周杰倫")
        status = 200
        body = { jayChou }
        ArtistAliases.learn(env, "周杰倫")
        assertEquals(2, queries.size)
        assertTrue(ArtistAliases.same("周杰伦", "jay chou"))
    }
}
